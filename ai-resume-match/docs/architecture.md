# AI Resume Match Architecture

本文档描述 `ai-resume-match` 完成后端工程化、前端产品化和 Agent 化后的当前架构，以及后续演进边界。

## 1. 系统概览

`ai-resume-match` 是一个 React + Spring Boot + FastAPI 全栈应用，用于上传简历、提交岗位 JD、异步生成并查看岗位匹配报告。

当前运行组件：

- Spring Boot 3.3 / Java 21 应用。
- FastAPI / Python 3.12+ Agent sidecar：执行受限 Tool Calling 循环和结构化结果校验。
- MySQL：保存简历、JD、分析任务、匹配报告和 outbox 事件。
- Redis：匹配报告查询的 cache-aside 缓存。
- RabbitMQ：异步分析任务队列。
- OpenAI-compatible API：为 Agent 选择工具和生成结构化参数。
- Agent 内本地稳定 hashing embedding + cosine similarity：配合 exact-term guard 和最低相关度 `0.08` 提供轻量证据检索，不相关查询可返回空结果；旧 Java RAG 实现保留为回退。
- React/Vite 前端：总览、历史、幂等提交、状态轮询、重试和报告呈现，页面路由使用 `lazy` + `Suspense` 按需加载。
- Nginx：托管静态资源、SPA fallback、同源 API 代理和运行时 token 注入。
- Docker Compose：本地可同时启动 frontend、app、agent、MySQL、Redis、RabbitMQ，并使用健康检查和持久化 volume。

系统以 Java 主应用作为业务事实和可靠性边界，仅把无状态的模型编排拆为 Python sidecar。该拆分是为了让 Agent runtime 可独立测试和替换，不把数据一致性或任务状态下沉到模型服务。

前端位于 `frontend/`，按 `app`、`features`、`shared` 分层；后端 Java 包结构如下。

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
- `analysis`：任务和报告实体、任务状态服务、worker 监听器、outbox publisher、陈旧 `RUNNING` 恢复、自动重试调度、RabbitMQ 配置、Redis report cache。
- `rag`：文本切分、embedding、向量检索、上下文构建。
- `ai`：OpenAI-compatible HTTP 客户端抽象和实现。

Agent 代码位于 `agent-service/agent_service`：

- `main.py`：内部 HTTP API、token 鉴权和异常映射。
- `agent.py`：有界模型/工具循环。
- `tools.py`：工具白名单、参数校验、调用顺序、逐条 claim 引用校验和安全 Markdown/evidence 映射渲染。
- `retrieval.py`：确定性切分、hashing embedding、exact-term guard、最低相关度过滤和稳定 evidence ID。
- `llm.py`：OpenAI-compatible Tool Calling 适配。
- `evaluation.py`：grounding、工具顺序、注入抵抗、分数、step 和延迟断言。

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

### 4.0 浏览器与认证边界

```text
Browser
  -> Nginx frontend:8080
  -> /assets + SPA fallback
  -> /api/* proxy
       -> inject X-API-Token from container environment
       -> Spring Boot app:8080
```

关键约束：

- 浏览器 bundle、DOM、local/session storage 都不包含 `API_TOKEN`。
- 浏览器只提交用户当前选择的简历和 JD，不把正文写入 Web Storage。
- Nginx 只在同源 `/api` 代理边界注入 token；开发环境由 Vite proxy 执行同样职责。
- Markdown 报告禁用远程图片，外部链接只允许 `http/https` 并使用安全的 `rel` 属性。
- Dashboard、历史、新建与详情四个页面入口通过 React `lazy` 和统一 `Suspense` fallback 做路由级代码分割。

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
- 提取正文超过 200,000 个 Unicode code point 时在 Java 边界拒绝，避免持久化后才被 Agent 的请求 schema 拒绝。
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
- `AnalysisOutboxPublisher` 先用 guarded update 将 due outbox 事件认领为 `PROCESSING`，再通过 RabbitMQ publisher confirm/return 判断投递结果；broker ack 且未 return 时标记 `PUBLISHED`，失败或不可路由时增加 attempt 并按 `nextAttemptAt` 重试。
- 单个事件受 `ANALYSIS_OUTBOX_MAX_ATTEMPTS` 限制（默认 10）；达到上限后进入不可自动认领的 `DEAD`，清空 `nextAttemptAt`，并通过 `analysis.outbox.events{outcome=dead}` 与 `analysis.outbox.backlog{status=dead}` 暴露。进入 `DEAD` 与业务同步处于 publisher 事务内：若关联任务仍为 `PENDING`，guarded update 将其标为 `FAILED_RETRYABLE/DELIVERY_FAILED`、`nextRetryAt=null`，用户可通过现有 retry 接口重建 outbox；启动后每轮还会 sweep 已耗尽但未终结的旧事件。`DEAD` 是 outbox 事件终态，不等于 exactly-once 或消息绝不丢失证明。

前端默认使用原子提交接口：

```text
POST /api/analysis-submissions (multipart file + jobTitle + jobContent)
  + optional Idempotency-Key
  -> validate and prepare resume
  -> SHA-256(key) + SHA-256(request fingerprint)
  -> create job description
  -> create PENDING analysis task
  -> create ANALYSIS_REQUESTED outbox event
  -> create analysis_submission_idempotency record when key is present
  -> commit all records in one transaction
```

旧的 `/api/resumes`、`/api/jobs`、`/api/analysis` 三步流程继续兼容；原子接口避免浏览器请求中途失败后留下孤立数据。`Idempotency-Key` 可选且只持久化 hash：同 key/同文件与 JD 指纹返回既有任务，同 key/不同指纹返回 `409 IDEMPOTENCY_CONFLICT`。前端在未修改提交内容的失败重试间复用 key，任一输入变化或提交成功后轮换。

### 4.4 Worker 与 Agent 生成报告

```text
RabbitMQ message(taskId)
  -> AnalysisWorker
  -> RunAnalysisUseCase
  -> AnalysisTaskService.tryStart
  -> load Resume and JobDescription from MySQL
  -> AnalysisEngine
       -> agent: AgentServiceAnalysisEngine -> POST /v1/agent/analyze
       -> legacy: LegacyRagAnalysisEngine -> local RAG -> AiClient.complete
  -> MatchReportRepository.save
  -> AnalysisTaskService.completeSuccess
```

Agent 模式内部流程：

```text
task ID（不含简历/JD/标题/标签）
  -> model chooses get_job_requirements
  -> model chooses search_resume_evidence(query, topK)
       -> exact-term + min-score filtering -> evidence IDs or empty result
  -> model calls submit_match_report(coreClaims/matchedSkills with per-claim evidenceIds)
  -> deterministic validation + claim citations + evidence map Markdown rendering
  -> Java validates taskId/score/report and persists result
```

关键约束：

- 消息体只包含 `taskId`。
- worker 不信任消息中的业务数据，必须从 MySQL 重取。
- Java 与 Agent 通过 `X-Agent-Token` 内部鉴权；请求保留 correlation ID，但双方日志均不记录正文、prompt 或工具输出。
- 初始模型上下文除固定指令外只包含 task ID；简历、JD、标题和标签仅由工具按阶段返回，并明确标记为不可信数据。
- Agent 只允许三个工具，使用 Pydantic 拒绝未知字段和越界参数；未知工具、错误顺序和引用非本轮非空检索结果的 evidence ID 均被拒绝。
- `coreClaims` 和 `matchedSkills` 的每条正向 claim 都必须带自己的 `evidenceIds`。检索无结果时仍可结束任务，但正向 claim 必须为空且分数不高于 30。
- 最终 Markdown 在每条正向 claim 后显示 evidence ID，并附 `evidence ID -> relevance score -> 已清理 excerpt` 映射，便于人工回查。
- 普通 assistant 文本不能结束任务；只有通过 `submit_match_report` 校验的结构化结果才能落库。
- step、工具调用和协议错误都有硬上限；每次 provider 请求还携带 `max_completion_tokens`，单次分析受累计 provider-reported total tokens 与累计序列化上下文字符硬预算约束。若 usage 不完整，`modelUsage.providerReported=false`，数字可能只是已报告部分而不是账单。
- 除 408/429 外的 4xx 与非法响应进入最终失败，网络、5xx、408 和 429 进入可重试失败。
- `match_report.task_id` 保持唯一，支撑幂等方向的演进。
- `AnalysisWorker` 只负责监听并委托 `RunAnalysisUseCase`；RAG、AI 调用、报告解析和失败分类在 use case 中编排。
- fresh `RUNNING` 任务不会因 redelivery 被直接重复认领。调度器扫描超过 `ANALYSIS_RUNNING_TIMEOUT` 的陈旧 `RUNNING`：未耗尽 attempts 时按状态/时间 guarded update 恢复为 `PENDING` 并在事务提交后重投，耗尽时转为 `FAILED_FINAL`。
- worker 领取后保存期望 attempt，成功/失败落库要求状态仍为 `RUNNING` 且 attempt 匹配；失去 lease 的迟到结果不会覆盖新一轮状态。该机制降低状态覆盖风险，但超时恢复仍可能造成外部模型工作重复，不构成 exactly-once 保证。
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

总览和历史使用批量读取接口：

```text
GET /api/analysis?status=&page=0&size=20
GET /api/analysis/summary
```

列表组装批量读取岗位名称、简历文件名和报告分数，避免逐行查询；岗位名称为空的旧数据由 API/前端显示兼容文案。

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
  -> frontend(Nginx, same-origin proxy)
  -> app(SPRING_PROFILES_ACTIVE=docker)
  -> agent(FastAPI, internal token, OpenAI-compatible API)
  -> mysql(service name: mysql)
  -> redis(service name: redis)
  -> rabbitmq(service name: rabbitmq)
```

当前 profile 约定：

- `dev`：默认 profile，用于本机 Maven 开发，连接 `localhost` 依赖，可使用开发占位 token/key。
- `docker`：用于 Compose app 容器，连接 Compose 服务名，凭据来自 `.env`。
- `prod`：用于生产或类生产环境，不包含本地默认凭据，所有敏感值由环境变量注入。
- `ANALYSIS_ENGINE=agent|legacy`：Compose 默认 `agent`；Java 配置默认 `legacy`，以兼容不启动 sidecar 的既有本地用法。

后端 Dockerfile 使用多阶段 Maven build、Java 21 runtime、非 root 用户和 Actuator readiness healthcheck。Agent Dockerfile 使用非 root 用户并提供进程/config 健康检查；前端 Dockerfile 使用固定 digest 的 Node build 与 unprivileged Nginx runtime。入口脚本拒绝 CR/LF token 并安全生成 Nginx 字面量。Compose 为 frontend、app、agent、MySQL、Redis、RabbitMQ 提供 healthcheck，并为有状态依赖配置 volume；`.env.example` 只记录示例值，真实 `.env` 不提交。应用 readiness 明确使用 Spring `readinessState`，Redis cache-aside 不参与阻断 readiness。

### 4.7 可观测性

HTTP 请求经过 `RequestCorrelationFilter`，生成或复用 `X-Request-Id` 和 `X-Correlation-Id`，并写入 SLF4J MDC。API 错误响应包含 `requestId`。

创建分析任务时，当前 `correlationId` 写入 outbox payload；outbox publisher 将它发布为 RabbitMQ header；worker 消费时恢复到 MDC，并在处理结束后清理。

任务生命周期日志使用 key-value 字段：`event`、`requestId`、`correlationId`、`taskId`、`resumeId`、`jobDescriptionId`、`attempt`、`failureCode`。分析失败按失败分类生成面向用户的固定消息，并清理控制字符、折叠空白和限制长度；outbox `lastError` 同样清理控制字符/空白并限制为 1024 字符；worker/Agent 失败日志记录异常类型而不是原始异常消息。该约束覆盖应用自有生命周期日志，不代表第三方库的所有日志都经过同一规则。

Micrometer 指标覆盖 `analysis.tasks.created`、`analysis.tasks.succeeded`、`analysis.tasks.failed`、`analysis.tasks.stale_leases`、`analysis.worker.duration`、`agent.calls`、`agent.call.duration`、`ai.calls`、`ai.call.duration`、`report.cache.requests`、`report.cache.writes`、`analysis.outbox.events` 和 `analysis.outbox.backlog`。Actuator 暴露 `/actuator/metrics`。Agent 响应另外返回 step、模型名、聚合 token usage、`providerReported` 和不含参数/正文的工具 trace，供离线评测使用；Java 只持久化业务所需的 score 与 Markdown 报告。

## 5. HTTP 契约

当前成功响应：

```json
{ "resumeId": 1 }
```

```json
{ "jobDescriptionId": 1, "title": "高级后端工程师" }
```

```json
{
  "taskId": 1,
  "resumeId": 1,
  "jobDescriptionId": 2,
  "jobTitle": "高级后端工程师",
  "resumeFileName": "resume.pdf",
  "matchScore": null,
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

兼容性约定：

- 创建 JD 时 `title` 可为空，支持重构前客户端与旧数据；新前端始终提交岗位名称。
- `FAILED` 只作为 legacy 状态读取，不再作为新任务流转目标。
- `Idempotency-Key` 对旧客户端是可选 header；key 与已存在记录的请求指纹冲突时返回 HTTP 409 和稳定错误码 `IDEMPOTENCY_CONFLICT`。

后续增强：

- 增加更细的业务错误码。
- 报告响应演进为结构化 JSON，同时保留文本 fallback。

## 6. 数据模型

当前核心实体：

- `Resume`：文件名、原始文本、结构化摘要。
- `JobDescription`：JD 内容、技能标签。
- `AnalysisTask`：简历 ID、JD ID、状态、attempts、失败码、失败消息、预留的下一次重试时间、开始/完成/创建/更新时间。
- `MatchReport`：任务 ID、匹配分数、报告正文、创建时间。
- `AnalysisOutboxEvent`：事件类型、聚合类型、聚合 ID、payload、投递状态（`PENDING`/`PROCESSING`/`PUBLISHED`/`FAILED`/`DEAD`）、attempts、下一次投递时间、最后错误、创建/发布时间。
- `AnalysisSubmissionIdempotencyRecord`：只保存 SHA-256 key hash、请求指纹、关联 task ID 和时间戳，用唯一约束协调并发重复提交。

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
RUNNING(stale, attempts remaining) -> PENDING
RUNNING(stale, attempts exhausted) -> FAILED_FINAL
FAILED_RETRYABLE -> PENDING
PENDING + outbox DEAD -> FAILED_RETRYABLE(DELIVERY_FAILED, manual retry)
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
- 前端产品化阶段加入原子提交、列表/汇总接口、React 工作台、路由级 lazy loading、稳定重试幂等 key、Nginx 同源代理、确定性 Playwright 场景和真实 Compose 浏览器验收。
- 可靠性收口加入提交 key hash/request fingerprint、409 冲突语义、陈旧 `RUNNING` 恢复、attempt completion fence、outbox 最大尝试/`DEAD`、失败消息清理及相关指标。
- Agent 化阶段加入 Python/FastAPI sidecar、`AnalysisEngine` 可替换端口、三工具白名单、有界循环、completion/total/context 预算、逐条 claim 引用、检索空结果、内部 token、Agent 指标、确定性单测和 5-case 合成评测集。
- Git 仓库根目录已配置 push/pull request GitHub Actions，覆盖 Java fast/集成、Python、前端质量/构建和浏览器 E2E；发布前远程运行已验证 5 个 jobs 全绿，浏览器 job 包含确定性 Playwright 与真实 Compose 全栈流程。

待实现：

- 当前 B+ 工程化重构计划内无剩余必做项；后续增强应作为新的阶段或需求单独设计。

## 9. 架构决策

- 保持 Spring Boot 为业务主系统，只拆出无状态 Agent runtime；MySQL、任务状态和重试仍由 Java 负责。
- MySQL 保持事实来源，Redis 和 RabbitMQ 都是派生或传输组件。
- 证据检索先保留本地 hashing embedding，后续可通过工具实现替换，不在当前阶段引入外部向量库。
- AI provider 通过 OpenAI-compatible Tool Calling client 抽象，避免 Agent 依赖具体供应商 SDK。
- 模型负责选择受限动作和生成候选结构；权限、顺序、资源预算、引用 ID 来源和最终接纳由确定性代码负责。当前代码不验证 claim 与 excerpt 的语义蕴含，技能差距、建议和面试题也仍是自由文本，因此不把引用来源校验表述为“事实真实性保证”。
- 前端不持有 API token；认证注入只发生在受控代理边界。
- 确定性浏览器测试负责 UI 分支与响应式回归，真实全栈验收负责跨进程契约，两者不相互替代。
- 每个阶段先保护行为契约，再做结构迁移。
