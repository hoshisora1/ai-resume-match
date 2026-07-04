# AI Resume Match Architecture

本文档描述 `ai-resume-match` 在 Phase 1 后的当前架构，以及工程化重构的目标边界。

## 1. 系统概览

`ai-resume-match` 是一个 Spring Boot 后端服务，用于上传简历、提交岗位 JD、异步生成岗位匹配报告。

当前运行组件：

- Spring Boot 3.3 / Java 21 应用。
- MySQL：保存简历、JD、分析任务、匹配报告。
- Redis：匹配报告查询的 cache-aside 缓存。
- RabbitMQ：异步分析任务队列。
- OpenAI-compatible API：生成匹配分析文本。
- 本地 hashing embedding + cosine similarity：提供轻量 RAG 检索能力。

当前系统仍是单体应用。重构目标不是拆微服务，而是在单体内形成清晰边界。

## 2. 当前包结构

```text
com.zhulikang.aimatch
  ai
  analysis
  api
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
- `analysis`：任务、报告、worker、RabbitMQ 配置、Redis report cache。
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
  -> AnalysisService.createTask
  -> after commit publish taskId to RabbitMQ
  -> AnalysisTaskResponse
```

当前 Phase 1 状态：

- API 返回 `taskId`、`resumeId`、`jobDescriptionId`、`status`。
- 任务初始状态为 `PENDING`。
- 可靠消息 outbox 尚未实现，属于 Phase 3 范围。

### 4.4 Worker 生成报告

```text
RabbitMQ message(taskId)
  -> AnalysisWorker
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
- worker 失败当前会标记任务失败；细分失败码、重试调度和 outbox 属于后续阶段。

### 4.5 查询任务和报告

```text
GET /api/analysis/{taskId}
  -> AnalysisService.findTask
  -> AnalysisTaskResponse

GET /api/analysis/{taskId}/report
  -> ReportCache lookup
  -> MatchReportRepository fallback
  -> cache successful report
  -> MatchReportView
```

关键约束：

- MySQL 是事实来源。
- Redis 缓存失败不能改变业务结果。
- 查询不存在的任务或报告返回 `404`。

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
  "message": "Invalid request"
}
```

计划中的增强：

- 增加 `requestId`。
- 增加更细的业务错误码。
- 任务状态响应增加 attempts、failureCode、failureMessage、startedAt、completedAt、nextRetryAt。

## 6. 数据模型

当前核心实体：

- `Resume`：文件名、原始文本、结构化摘要。
- `JobDescription`：JD 内容、技能标签。
- `AnalysisTask`：简历 ID、JD ID、状态、创建和更新时间。
- `MatchReport`：任务 ID、匹配分数、报告正文、创建时间。

目标实体增强：

- `AnalysisTask` 增加 attempts、maxAttempts、failureCode、failureMessage、nextRetryAt、startedAt、completedAt。
- `MatchReport` 增加 `reportJson`，保留 `reportMarkdown`。
- 增加 `analysis_outbox` 表，支撑可靠投递。
- 简历和 JD 增加删除标记、内容 hash、文件大小、content type 等审计字段。

## 7. 任务状态

当前状态较简单：

```text
PENDING
RUNNING
SUCCESS
FAILED
```

目标状态：

```text
PENDING
RUNNING
SUCCESS
FAILED_RETRYABLE
FAILED_FINAL
CANCELLED
```

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

待实现：

- Flyway schema migration。
- outbox publisher。
- worker 原子 claim 和幂等跳过。
- retryable/final 失败分类。
- RabbitMQ、Redis、MySQL Testcontainers 验证。
- 结构化日志、request ID、任务生命周期 metrics。

## 9. 架构决策

- 保持 Spring Boot 单体，优先强化边界和测试，不急于拆服务。
- MySQL 保持事实来源，Redis 和 RabbitMQ 都是派生或传输组件。
- RAG 先保留本地 hashing embedding，后续通过接口替换，不在当前阶段引入外部向量库。
- AI provider 通过 OpenAI-compatible client 抽象，避免业务层依赖具体供应商 SDK。
- 每个阶段先保护行为契约，再做结构迁移。
