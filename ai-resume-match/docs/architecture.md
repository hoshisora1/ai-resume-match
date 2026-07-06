# AI Resume Match Architecture

本文档描述 `ai-resume-match` 在 Phase 5 后的当前架构，以及工程化重构的目标边界。

## 1. 系统概览

`ai-resume-match` 是一个 Spring Boot 后端服务，用于上传简历、提交岗位 JD、异步生成岗位匹配报告。

当前运行组件：

- Spring Boot 3.3 / Java 21 应用。
- MySQL：保存简历、JD、分析任务、匹配报告和 outbox 事件。
- Redis：匹配报告查询的 cache-aside 缓存。
- RabbitMQ：异步分析任务队列。
- OpenAI-compatible API：生成匹配分析文本。
- 本地 hashing embedding + cosine similarity：提供轻量 RAG 检索能力。
- Docker Compose：本地可同时启动 app、MySQL、Redis、RabbitMQ，并使用健康检查和持久化 volume。

当前系统仍是单体应用。重构目标不是拆微服务，而是在单体内形成清晰边界。

## 2. 当前包结构

```text
com.zhulikang.aimatch
  ai
  analysis
  api
  application
    analysis
    job
    report
    resume
  document
  job
  rag
  resume
```

当前职责：

- `api`：HTTP controller、请求 DTO、响应 DTO、API token、异常处理。
- `resume`：简历实体与仓储。
- `job`：JD 实体、仓储、技能标签提取。
- `document`：PDF/DOCX 文本提取和上传文件校验。
- `application`：上传简历、创建 JD、创建/查询/重试/运行分析任务、查询报告等用例编排。
- `analysis`：任务和报告实体、任务状态服务、worker 监听器、outbox publisher、自动重试调度、RabbitMQ 配置、Redis report cache。
- `rag`：文本切分、embedding、向量检索、上下文构建。
- `ai`：OpenAI-compatible HTTP 客户端抽象和实现。

## 3. 目标分层

后续阶段推荐逐步演进到：

```text
com.zhulikang.aimatch
  api
    auth
    dto
    error
    web
  application
    analysis
    job
    resume
    report
  domain
    analysis
    job
    resume
    report
  infrastructure
    ai
    db
    mq
    redis
    document
    config
  rag
    chunking
    embedding
    retrieval
    prompt
    report
  observability
```

迁移原则：

- Controller 只处理 HTTP 协议、DTO 转换和状态码。
- application 层承载用例编排，例如上传简历、创建 JD、创建分析任务、运行分析、查询报告、重试任务。
- domain 层承载实体状态转换、业务不变量和失败分类。
- infrastructure 层承载 JPA、RabbitMQ、Redis、AI HTTP、文档解析和配置适配。
- RAG 相关能力保持可替换，避免和 worker 强耦合。

## 4. 主要数据流

### 4.1 上传简历

```text
POST /api/resumes
  -> ApiTokenInterceptor
  -> ResumeFileValidator
  -> DocumentTextExtractor
  -> ResumeRepository.save
  -> ResumeUploadResponse
```

关键约束：

- 校验必须发生在解析前。
- 解析结果为空时拒绝请求。
- 当前保存原始文本和结构化文本字段，后续应明确保留周期与删除策略。

### 4.2 创建 JD

```text
POST /api/jobs
  -> request validation
  -> JdTagExtractor
  -> JobDescriptionRepository.save
  -> JobDescriptionResponse
```

关键约束：

- JD 内容不能为空。
- 技能标签是后续 RAG prompt 的输入之一。

### 4.3 创建分析任务

```text
POST /api/analysis
  -> check resume exists
  -> check job exists
  -> CreateAnalysisTaskUseCase.create
  -> save analysis_task(PENDING)
  -> save analysis_outbox(ANALYSIS_REQUESTED)
  -> AnalysisOutboxPublisher publishes taskId to RabbitMQ
  -> AnalysisTaskResponse
```

当前 Phase 3 状态：

- API 返回 `taskId`、`resumeId`、`jobDescriptionId`、`status`、attempt/failure/timestamp 元数据。
- 任务初始状态为 `PENDING`，同一事务写入 `analysis_outbox`。
- `AnalysisOutboxPublisher` 先用 guarded update 将 due outbox 事件认领为 `PROCESSING`，再通过 RabbitMQ publisher confirm/return 判断投递结果；broker ack 且未 return 时标记 `PUBLISHED`，失败或不可路由时标记 `FAILED` 并按 `nextAttemptAt` 重试。

### 4.4 Worker 生成报告

```text
RabbitMQ message(taskId)
  -> AnalysisWorker
  -> RunAnalysisUseCase
  -> AnalysisTaskService.tryStart
  -> load Resume and JobDescription from MySQL
  -> TextChunker
  -> InMemoryVectorStore
  -> RagContextBuilder
  -> AiClient.complete
  -> ReportParser.extractScore
  -> MatchReportRepository.save
  -> AnalysisTaskService.completeSuccess
```

关键约束：

- 消息体只包含 `taskId`。
- worker 不信任消息中的业务数据，必须从 MySQL 重取。
- `match_report.task_id` 保持唯一，支撑幂等方向的演进。
- `AnalysisWorker` 只负责监听并委托 `RunAnalysisUseCase`；RAG、AI 调用、报告解析和失败分类在 use case 中编排。
- `redelivered=true` 不会绕过 `RUNNING` stale 判断；fresh `RUNNING` 任务会被跳过，只有 `PENDING` 或 stale `RUNNING` 可被认领。
- 运行失败会落到 `FAILED_RETRYABLE` 或 `FAILED_FINAL`，并记录失败码、失败消息、attempts 和 `nextRetryAt`。
- 自动重试调度会把 due 的 `FAILED_RETRYABLE` 任务重置为 `PENDING`，并通过 outbox 重新投递。

### 4.5 查询任务和报告

```text
GET /api/analysis/{taskId}
  -> GetAnalysisTaskUseCase.find
  -> AnalysisTaskResponse

GET /api/analysis/{taskId}/report
  -> GetMatchReportUseCase.find
  -> ReportCache lookup
  -> MatchReportRepository fallback
  -> cache successful report
  -> MatchReportView
```

```text
POST /api/analysis/{taskId}/retry
  -> RetryAnalysisTaskUseCase.retry
  -> FAILED_RETRYABLE -> PENDING
  -> save analysis_outbox(ANALYSIS_REQUESTED)
  -> AnalysisTaskResponse
```

关键约束：

- MySQL 是事实来源。
- Redis 缓存失败不能改变业务结果。
- 查询不存在的任务或报告返回 `404`。

### 4.6 运行配置与部署

```text
docker compose
  -> app(SPRING_PROFILES_ACTIVE=docker)
  -> mysql(service name: mysql)
  -> redis(service name: redis)
  -> rabbitmq(service name: rabbitmq)
```

当前 profile 约定：

- `dev`：默认 profile，用于本机 Maven 开发，连接 `localhost` 依赖，可使用开发占位 token/key。
- `docker`：用于 Compose app 容器，连接 Compose 服务名，凭据来自 `.env`。
- `prod`：用于生产或类生产环境，不包含本地默认凭据，所有敏感值由环境变量注入。

Dockerfile 使用多阶段 Maven build、Java 21 runtime、非 root 用户和 Actuator readiness healthcheck。Compose 为 app、MySQL、Redis、RabbitMQ 配置 healthcheck 和 volume；`.env.example` 只记录示例值，真实 `.env` 不提交。应用 readiness 明确使用 Spring `readinessState`，依赖服务健康由 Compose healthcheck 和启动期连接/迁移结果覆盖，Redis cache-aside 不参与阻断 readiness。

### 4.7 可观测性

HTTP 请求经过 `RequestCorrelationFilter`，生成或复用 `X-Request-Id` 和 `X-Correlation-Id`，并写入 SLF4J MDC。API 错误响应包含 `requestId`。

创建分析任务时，当前 `correlationId` 写入 outbox payload；outbox publisher 将它发布为 RabbitMQ header；worker 消费时恢复到 MDC，并在处理结束后清理。

任务生命周期日志使用 key-value 字段：`event`、`requestId`、`correlationId`、`taskId`、`resumeId`、`jobDescriptionId`、`attempt`、`failureCode`。日志只记录 ID、状态和失败分类，不记录简历原文、JD 原文、prompt 或 AI 响应正文。

Micrometer 指标覆盖 `analysis.tasks.created`、`analysis.tasks.succeeded`、`analysis.tasks.failed`、`analysis.worker.duration`、`ai.calls`、`ai.call.duration`、`report.cache.requests`、`report.cache.writes`、`analysis.outbox.events` 和 `analysis.outbox.backlog`。Actuator 暴露 `/actuator/metrics`。

## 5. HTTP 契约

当前成功响应：

```json
{ "resumeId": 1 }
```

```json
{ "jobDescriptionId": 1 }
```

```json
{
  "taskId": 1,
  "resumeId": 1,
  "jobDescriptionId": 2,
  "status": "PENDING",
  "createdAt": "2026-07-04T09:30:00",
  "updatedAt": "2026-07-04T09:30:00"
}
```

当前错误响应：

```json
{
  "code": "INVALID_REQUEST",
  "message": "Invalid request",
  "requestId": "2f6b6a0d-45c5-4b65-bbc1-3aaf70c79cb4"
}
```

计划中的增强：

- 增加更细的业务错误码。
- 报告响应演进为结构化 JSON，同时保留文本 fallback。

## 6. 数据模型

当前核心实体：

- `Resume`：文件名、原始文本、结构化摘要。
- `JobDescription`：JD 内容、技能标签。
- `AnalysisTask`：简历 ID、JD ID、状态、attempts、失败码、失败消息、预留的下一次重试时间、开始/完成/创建/更新时间。
- `MatchReport`：任务 ID、匹配分数、报告正文、创建时间。
- `AnalysisOutboxEvent`：事件类型、聚合类型、聚合 ID、payload、投递状态（`PENDING`/`PROCESSING`/`PUBLISHED`/`FAILED`）、attempts、下一次投递时间、最后错误、创建/发布时间。

目标实体增强：

- `MatchReport` 增加 `reportJson`，保留 `reportMarkdown`。
- 简历和 JD 增加删除标记、内容 hash、文件大小、content type 等审计字段。

## 7. 任务状态

当前状态：

```text
PENDING
RUNNING
SUCCESS
FAILED_RETRYABLE
FAILED_FINAL
CANCELLED
```

`FAILED` 仍作为兼容旧数据的 legacy enum value 保留，不再作为新任务流转目标。

目标状态迁移：

```text
PENDING -> RUNNING
RUNNING -> SUCCESS
RUNNING -> FAILED_RETRYABLE
RUNNING -> FAILED_FINAL
FAILED_RETRYABLE -> PENDING
PENDING -> CANCELLED
FAILED_RETRYABLE -> CANCELLED
```

`SUCCESS`、`FAILED_FINAL`、`CANCELLED` 为终态。重新分析应创建新任务，而不是覆盖历史任务。

## 8. 可靠性边界

已具备：

- worker 消息只传 ID。
- 报告按 taskId 唯一。
- Redis 只是缓存。
- Phase 1 加入明确 API 契约和上传校验。
- Phase 2 加入 application use case、原子任务状态流转、retryable/final 失败分类、手动 retry 入口和薄 worker。
- Phase 3 加入 Flyway 初始 schema、analysis outbox、带 claim 和 publisher confirm/return 的 outbox publisher、自动重试调度，以及 MySQL/RabbitMQ Testcontainers 验证。
- Phase 4 加入 Dockerfile、app compose service、health checks、`dev`/`docker`/`prod` profiles、`.env.example` 和部署配置契约测试。
- Phase 5 加入 request ID、correlation ID、结构化任务生命周期日志、Micrometer 指标和运行手册。
- Phase 6 加入 Redis Testcontainers、mock AI HTTP server、PDF/DOCX fixtures，以及覆盖上传、任务创建、outbox、RabbitMQ worker、AI 调用、报告查询和 Redis cache-aside 的端到端分析流验证。

待实现：

- 当前 B+ 工程化重构计划内无剩余必做项；后续增强应作为新的阶段或需求单独设计。

## 9. 架构决策

- 保持 Spring Boot 单体，优先强化边界和测试，不急于拆服务。
- MySQL 保持事实来源，Redis 和 RabbitMQ 都是派生或传输组件。
- RAG 先保留本地 hashing embedding，后续通过接口替换，不在当前阶段引入外部向量库。
- AI provider 通过 OpenAI-compatible client 抽象，避免业务层依赖具体供应商 SDK。
- 每个阶段先保护行为契约，再做结构迁移。
