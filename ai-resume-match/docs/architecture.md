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
- Agent 检索默认使用本地稳定 hashing + cosine、exact-term guard 和最低相关度 `0.08`；显式 `hybrid` 模式会调用 OpenAI-compatible embedding endpoint，以 dense cosine 与 hashing 做 RRF 融合，并在单次分析内缓存 document/query vectors。两种实现都返回相同稳定 evidence ID 与 `sourceStart/sourceEnd`，可在同一 span/qrels 数据集上比较。hybrid 尚无已提交的真实 provider 质量结果，不作为默认值。旧 Java RAG 仅保留为显式基线。
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
- `document`：PDF/DOCX 扩展名与内容签名校验、PDF 页数和 DOCX 解压资源边界、段落/表格文本提取。
- `application`：上传简历、创建 JD、创建/查询/重试/运行分析任务、查询报告等用例编排。
- `analysis`：任务和报告实体、任务状态服务、worker 监听器、outbox publisher、陈旧 `RUNNING` 恢复、自动重试调度、RabbitMQ 配置、Redis report cache。
- `config`：类型化 `ai/agent/api/report/analysis/resume` 配置、Bean Validation 与跨字段预算约束；生产组件不直接读取 `@Value`。
- `rag`：文本切分、embedding、向量检索、上下文构建。
- `ai`：OpenAI-compatible HTTP 客户端抽象和实现。

Agent 代码位于 `agent-service/agent_service`：

- `main.py`：内部 HTTP API、token 鉴权和异常映射。
- `agent.py`：有界模型/工具循环。
- `tools.py`：工具白名单、参数校验、调用顺序、逐条 claim 引用校验、`match-report-v2` 构建和安全 Markdown 兼容渲染。
- `retrieval.py`：统一 Retriever/Embedding 协议、确定性切分、hashing/dense 实现、RRF hybrid、阈值过滤与稳定 evidence ID。
- `embeddings.py`：OpenAI-compatible embedding adapter、批处理、响应维度/数值校验和脱敏错误边界。
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

AI 数据最小化边界位于 `RunAnalysisUseCase`：数据库只保存一份受 owner/retention 管理的原始提取文本，V9 已删除曾与它重复的 `structured_summary`。worker 读取后先经 `ModelInputPrivacySanitizer`，再把替换过的简历、JD、标题和标签传给 `AnalysisEngine`。因此 Agent 与显式 legacy 基线共享同一脱敏边界。脱敏器只输出固定 marker 与按类别聚合的 Micrometer 计数，日志不包含原始命中内容；规则覆盖不等同于完整实体识别。

## 4. 主要数据流

### 4.0 浏览器与认证边界

```text
Browser
  -> Nginx frontend:8080
  -> /assets + SPA fallback
  -> /api/* proxy
       -> inject X-API-Token from container environment
       -> Spring Boot app:8080
            -> validate signed HttpOnly anonymous-session cookie or issue one
            -> bind SHA-256(session id) as request owner
```

关键约束：

- 浏览器 bundle、DOM、local/session storage 都不包含 `API_TOKEN` 或匿名会话 bearer。
- 浏览器只提交用户当前选择的简历和 JD，不把正文写入 Web Storage。
- Nginx 只在同源 `/api` 代理边界注入网关 token；开发环境由 Vite proxy 执行同样职责。它不再代表所有访客共享同一业务身份。
- Spring 对授权请求签发 HMAC-SHA256 签名、带过期时间的 `HttpOnly + SameSite=Strict` Cookie；数据库只保存稳定 session id 的 SHA-256。`resume/job_description/analysis_task/analysis_submission_idempotency` 均带 owner，API 查询、汇总、报告缓存读取前检查和重试都限定 owner。
- `RequestIdentity` 缺失时立即失败；`OwnerId.LEGACY` 仅标识迁移数据，不提供跨 owner 查询特权。报告查询的任务 repository 是必需依赖，缓存命中也必须先检查所属身份。后台 worker 按消息中的任务 ID 工作，不依赖请求线程身份。
- 分析创建、原子提交和重试在进入业务用例前通过 Redis Lua 原子计数；超限返回 `429 + Retry-After`，Redis 故障返回 503，避免配额失效时继续产生模型费用。
- 该匿名身份只适合无需登录的作品 Demo；正式账户、跨设备恢复和长期公网服务仍应使用 OIDC/JWT，并把相同 owner 查询不变量绑定到稳定 subject。
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
- `AnalysisOutboxPublisher` 用三个独立的 `REQUIRES_NEW` 短事务完成候选扫描、单事件认领和结果落库。认领 guarded update 写入随机 `leaseToken` 与 `leaseUntil` 后立即提交；RabbitMQ send/confirm/return 在事务外等待，因此网络阻塞不会长期占用 InnoDB 事务或行锁。
- broker ack 且未 return 时，结果事务只有在 `id + PROCESSING + leaseToken` 仍匹配时才标记 `PUBLISHED`；失败、不可路由或中断也使用同一 fencing 条件。租约过期后其他实例可以生成新 token 重新认领，旧实例的迟到成功或失败不会覆盖新一轮状态。`ANALYSIS_OUTBOX_LEASE_DURATION` 默认 30 秒且必须大于 confirm timeout。
- 单个事件受 `ANALYSIS_OUTBOX_MAX_ATTEMPTS` 限制（默认 10）；失败后按 `base * 2^(attempt-1)` 计算 capped exponential backoff，默认以 `0.2` 比例加入对称 jitter，并将结果限制在 `ANALYSIS_OUTBOX_RETRY_MAX_DELAY=15m` 内。达到上限后进入不可自动认领的 `DEAD`，清空 retry/lease 字段，并通过 `analysis.outbox.events{outcome=dead}` 与 `analysis.outbox.backlog{status=dead}` 暴露。失败收敛到 `DEAD` 与关联业务状态同步处于同一个短事务：若关联任务仍为 `PENDING`，guarded update 将其标为 `FAILED_RETRYABLE/DELIVERY_FAILED`、`nextRetryAt=null`，用户可通过现有 retry 接口重建 outbox；每轮还会 sweep 已耗尽但未终结的旧事件。`DEAD` 和 lease fencing 仍不等于 exactly-once 或消息绝不丢失证明。
- Flyway V7 增加 `terminal_at` 与终态清理索引。retention scheduler 默认每小时分批删除终态超过 30 天的 `PUBLISHED/DEAD` outbox，以及超过 30 天、未绑定任务或仅绑定 `SUCCESS/FAILED_FINAL/CANCELLED/FAILED` 终态任务的提交幂等记录；绑定 `PENDING/RUNNING/FAILED_RETRYABLE` 的记录会保留。
- 用户数据保留由独立 guard 执行：`ANALYSIS_USER_DATA_RETENTION=30d` 到期后，按 `created_at` 选择所有状态并在行锁内再次校验，按 report/idempotency/outbox → task → orphan resume/job 的顺序事务删除，并 best-effort 驱逐 Redis。主动 `DELETE /api/analysis/{taskId}` 使用相同服务和 owner 行锁；迟到 worker 必须先通过 task/status/attempt 更新，因此无法重建已删除报告。调度线程停机时最多等待 `SCHEDULER_SHUTDOWN_TIMEOUT=10s` 完成当前批次。

前端默认使用原子提交接口：

```text
POST /api/analysis-submissions (multipart file + jobTitle + jobContent)
  + optional Idempotency-Key
  -> validate and prepare resume
  -> SHA-256(owner + SHA-256(key)) + SHA-256(request fingerprint)
  -> create job description
  -> create PENDING analysis task
  -> create ANALYSIS_REQUESTED outbox event
  -> create analysis_submission_idempotency record when key is present
  -> commit all records in one transaction
```

旧的 `/api/resumes`、`/api/jobs`、`/api/analysis` 三步流程继续兼容；原子接口避免浏览器请求中途失败后留下孤立数据。`Idempotency-Key` 可选且只持久化 owner-scoped hash：同一会话中同 key/同文件与 JD 指纹返回既有任务，同 key/不同指纹返回 `409 IDEMPOTENCY_CONFLICT`，不同会话使用相同原始 key 不冲突。前端在未修改提交内容的失败重试间复用 key，任一输入变化或提交成功后轮换。

两个异步创建入口都返回 `202 Accepted`，响应体仍是任务快照，并用 `Location: /api/analysis/{taskId}` 指向轮询资源。已处理的 API 错误统一为 `application/problem+json`；提交到仓库的 `api/openapi.json` 由 Spring 应用生成并做精确快照测试。报告 API 使用显式 response-only wire DTO 展开 requirement/evidence/score/provenance/run metadata，不再把核心字段声明为任意 `JsonNode`；前端从规范生成 TypeScript 类型，Zod schema 以 `satisfies` 与双向精确类型断言绑定生成类型，CI 连续检查 Java→OpenAPI→生成类型→Zod 四段漂移。

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
       -> runtime extracts <=5 requirementId/text/mustHave/weight records
  -> model chooses search_resume_evidence(requirementId, query, topK) for every requirement
       -> exact-term + min-score filtering -> evidence IDs or empty result
  -> model calls submit_match_report(requirementAssessments)
  -> same-requirement evidence validation
  -> conservative lexical/negation verifier can preserve or downgrade status
  -> deterministic weighted score over final statuses
  -> match-report-v2 + derived claims/gaps + final cited evidence + Markdown fallback
  -> Java independently validates score/status/evidence graph
  -> MySQL persists schema version + structured JSON + safe provenance + Markdown
```

关键约束：

- 消息体只包含 `taskId`。
- worker 不信任消息中的业务数据，必须从 MySQL 重取。
- Java 与 Agent 通过 `X-Agent-Token` 内部鉴权；请求保留 correlation ID，但双方日志均不记录正文、prompt 或工具输出。
- 初始模型上下文除固定指令外只包含 task ID；简历、JD、标题和标签仅由工具按阶段返回，并明确标记为不可信数据。
- Agent 只允许三个工具，使用 Pydantic 拒绝未知字段和越界参数；未知工具、错误顺序、未知 requirement ID、漏检 requirement，以及引用并非为同一 requirement 检索到的 evidence ID 均被拒绝。
- 工具模型直接继承共享 API 配置，`topK` 与 `evidenceIds` 显式必填以满足 strict Schema；评分直接使用已验证的 requirement results，避免重复构造一份 assessments。
- JD 条款先于技能标签参与提取；标签只补充未覆盖项，必需/加分标记仅作用于本条款或显式标题下的条目。按硬性要求优先选择最多 5 项，评分范围限定为这份要求列表。
- 模型提交每项 requirement 的 `supported/partial/not_found + explanation + evidenceIds`，不再提交分数或自由的正向 claim。`supported/partial` 必须有同 requirement 证据，`not_found` 不得引用证据。
- `conservative-lexical-negation-v2` 对引用片段做字面技术名边界、术语覆盖和邻近中英文否定检查，避免 JavaScript/Java、NoSQL/SQL、storage/RAG 的子串误匹配；最终 status 取模型提议与 verifier 决策中更保守者，verifier 异常按 `not_found` 收口。它是规则护栏，不是经过人工标注校准的语义蕴含模型。
- 运行时代码按最终 status、requirement weight 与固定系数 `1/0.5/0` 计算 0–100 分；must-have 为 `not_found` 时最高 69 分。
- 最终响应包含 `match-report-v2`、`verifierVersion` 和 `requirementResults`；每项包含 model/final status、coverage、reason、accepted evidence IDs。结构化报告只保留最终被引用 evidence，并携带可由 requirement 重新计算的 `scoreBreakdown`；Markdown 从同一结果派生用于兼容。
- 普通 assistant 文本不能结束任务；只有通过 `submit_match_report` 校验的结构化结果才能落库。
- step、工具调用和协议错误都有硬上限；每次 provider 请求还携带 `max_completion_tokens`，单次分析受累计 provider-reported total tokens 与累计序列化上下文字符硬预算约束。Agent 进程另以 `AGENT_MAX_CONCURRENT_ANALYSES` 限制同时运行的已授权分析；容量等待超过 0.1 秒时，在 chat/embedding 调用前返回结构化 `503 AGENT_CAPACITY_EXHAUSTED`，由 Java 的任务退避策略接管。若 usage 不完整，`modelUsage.providerReported=false`，数字可能只是已报告部分而不是账单。只有部署同时提供定价版本和输入/输出每百万 token 美元单价时才启用 Decimal 成本估算；usage 完整时响应增加 `estimatedCostUsd/pricingVersion`，否则保持 `null`。估算只覆盖 chat prompt/completion token，不覆盖 embedding、缓存 token 特价、存储、网络、税费等额外项目。响应同时携带固定 `promptVersion`、`retrieverVersion` 和 `agent-run-v1`：后者记录请求/运行时版本、task-scoped 输入指纹、Chat/Tool/总耗时、调用次数与上下文字符。Java 使用同一长度前缀算法重算指纹，并验证调用/耗时闭包后才持久化。
- 除 408/429 外的 4xx 与非法响应进入最终失败，网络、5xx、408 和 429 进入可重试失败。
- `match_report.task_id` 保持唯一，支撑幂等方向的演进。
- `AnalysisWorker` 只负责监听并委托 `RunAnalysisUseCase`；RAG、AI 调用、报告解析和失败分类在 use case 中编排。
- fresh `RUNNING` 任务不会因 redelivery 被直接重复认领。调度器扫描超过 `ANALYSIS_RUNNING_TIMEOUT` 的陈旧 `RUNNING`：未耗尽 attempts 时按状态/时间 guarded update 恢复为 `PENDING` 并在事务提交后重投，耗尽时转为 `FAILED_FINAL`。
- worker 领取后保存期望 attempt，成功/失败落库要求状态仍为 `RUNNING` 且 attempt 匹配；失去 lease 的迟到结果不会覆盖新一轮状态。该机制降低状态覆盖风险，但超时恢复仍可能造成外部模型工作重复，不构成 exactly-once 保证。
- 运行失败会落到 `FAILED_RETRYABLE` 或 `FAILED_FINAL`，并记录失败码、失败消息、attempts 和 `nextRetryAt`。可重试任务按 `base * 2^(attempt-1)` 计算有上限的指数退避，再加入有界 jitter；Agent 错误契约中的正数 `retryAfterSeconds` 被视为最短等待时间，最终结果仍限制在 `ANALYSIS_RETRY_MAX_DELAY` 内。这样既尊重 provider 的限流窗口，也不会让不可信上游值无限推迟任务。
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
- `ANALYSIS_ENGINE=agent|legacy`：Java 与 Compose 均默认 `agent`；`legacy` 仅用于显式基线对比或兼容验证，不是自动降级链路。

后端 Dockerfile 使用多阶段 Maven build、Java 21 runtime、非 root 用户和 Actuator readiness healthcheck。Agent Dockerfile 使用非 root 用户并提供进程/config 健康检查；前端 Dockerfile 使用固定 digest 的 Node build 与 unprivileged Nginx runtime。入口脚本拒绝 CR/LF token 并安全生成 Nginx 字面量。Compose 为 frontend、app、agent、MySQL、Redis、RabbitMQ 提供 healthcheck，并为有状态依赖配置 volume；`.env.example` 只记录示例值，真实 `.env` 不提交。应用 readiness 由 Spring `readinessState` 与 MySQL `db` 共同决定；Redis cache-aside 不参与阻断 readiness。

### 4.7 可观测性

HTTP 请求经过 `RequestCorrelationFilter`，生成或复用 `X-Request-Id` 和 `X-Correlation-Id`，并写入 SLF4J MDC。API 错误响应包含 `requestId`。

创建分析任务时，当前 `correlationId` 写入 outbox payload；outbox publisher 将它发布为 RabbitMQ header；worker 消费时恢复到 MDC，并在处理结束后清理。

可选 tracing 使用 W3C Trace Context 与 OTLP/HTTP。API 请求的 `traceparent` 在同一事务创建 outbox 时持久化；publisher 在网络 I/O 前把它恢复为远程父上下文，RabbitTemplate/listener observation 继续 producer/consumer span，随后 Spring 管理的 `RestTemplate` 将上下文传给 Python FastAPI。Agent 的 chat/embedding HTTPX 客户端继续同一 trace，成功响应把 32 位 trace ID 写入 `analysis-run-v1`，供报告页和 live eval artifact 回查。基础配置默认关闭导出，`docker-compose.observability.yml` 同时为 Java/Agent 开启并写入本地 Tempo。

任务生命周期日志使用 key-value 字段：`event`、`requestId`、`correlationId`、`taskId`、`resumeId`、`jobDescriptionId`、`attempt`、`failureCode`。分析失败按失败分类生成面向用户的固定消息，并清理控制字符、折叠空白和限制长度；outbox `lastError` 同样清理控制字符/空白并限制为 1024 字符；worker/Agent 失败日志记录异常类型而不是原始异常消息。`SensitiveTelemetryPolicyTest` 对自有 Java/Python 日志、metric tag 和 trace attribute/event 做 source-level 门禁，Java/Python 成功路径再用正文 canary 检查捕获日志。该约束不代表第三方库或未来生产 trace 已经过完整 DLP 扫描。

Micrometer 指标覆盖 `analysis.tasks.created`、`analysis.tasks.succeeded`、`analysis.tasks.failed`、`analysis.tasks.stale_leases`、`analysis.worker.duration`、`agent.calls`、`agent.call.duration`、`ai.calls`、`ai.call.duration`、`report.cache.requests`、`report.cache.writes`、`report.cache.evictions`、`analysis.outbox.events`、`analysis.outbox.backlog`、`analysis.outbox.oldest.age` 和 `analysis.retention.deleted`。Actuator 暴露 `/actuator/metrics` 与 `/actuator/prometheus`；耗时指标启用可聚合 histogram。Python `/metrics` 另外暴露 `agent_service_*` 系列：整次分析/provider/tool 的 outcome 与 duration、in-progress、分析容量/容量拒绝、reported token、retrieval hit/empty/evidence count、协议拒绝、鉴权失败、最终 requirement status、版本化 USD 估算和估算覆盖率。其标签经过固定 allowlist 收敛，不使用 task/correlation ID、输入指纹、query、正文、异常消息、模型输出、单价或动态工具/版本名。可选 `docker-compose.observability.yml` 同时抓取 Java 与 Agent，启动仅绑定本机的 Prometheus/Grafana/Tempo，预置 15 个吞吐、p95、outbox、缓存、token、检索、工具、协议、容量和成本面板、Tempo 数据源，以及两端 target down、outbox、任务/provider 失败率、Agent 协议/容量错误和“启用价格但 usage 缺失”规则。Agent 响应另外返回 step、模型名、聚合 token usage、`providerReported`、trace ID、不含参数/正文的工具 trace 与 `agent-run-v1`。Java 独立验证成本/pricing version、输入指纹与运行关系，再将安全元数据保存为 `analysis-run-v1` provenance；不保存工具参数、输入正文或隐藏推理。当前尚未接入 Alertmanager 通知、provider 账单对账或生产级 trace 访问控制/长期保留。

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

Agent 生成的新报告使用版本化结构；旧数据仍以 `markdown-v1` 返回：

```json
{
  "taskId": 1,
  "matchScore": 100,
  "reportContent": "匹配分数: 100 ...",
  "reportSchemaVersion": "match-report-v2",
  "structuredReport": {
    "schemaVersion": "match-report-v2",
    "matchScore": 100,
    "requirements": [{
      "requirementId": "requirement:0",
      "text": "Java",
      "mustHave": true,
      "weight": 2,
      "modelStatus": "supported",
      "status": "supported",
      "explanation": "引用片段覆盖该要求。",
      "evidenceIds": ["resume:0"],
      "verification": {
        "verifierVersion": "conservative-lexical-negation-v2",
        "status": "supported",
        "termCoverage": 1,
        "reason": "required_terms_supported",
        "evidenceIds": ["resume:0"]
      }
    }],
    "coreClaims": [{
      "claim": "满足 Java 要求。",
      "evidenceIds": ["resume:0"]
    }],
    "matchedSkills": [],
    "skillGaps": ["未发现基于当前岗位要求的证据缺口。"],
    "recommendations": ["补充指标", "补充压测", "补充告警"],
    "interviewQuestions": ["如何限流？", "如何重试？", "如何评测？"],
    "evidence": [{
      "evidenceId": "resume:0",
      "excerpt": "Built Java services.",
      "score": 0.9,
      "sourceStart": 0,
      "sourceEnd": 20
    }],
    "scoreBreakdown": {
      "rawScore": 100,
      "finalScore": 100,
      "totalWeight": 2,
      "supportedWeight": 2,
      "partialWeight": 0,
      "missingWeight": 0,
      "mustHaveCapApplied": false
    }
  },
  "provenance": {
    "schemaVersion": "analysis-run-v1",
    "correlationId": "example-1",
    "traceId": "0123456789abcdef0123456789abcdef",
    "model": "provider-model",
    "promptVersion": "requirement-verified-agent-v3",
    "retrieverVersion": "hashing-256-v1",
    "verifierVersion": "conservative-lexical-negation-v2",
    "steps": 3,
    "runMetadata": {
      "schemaVersion": "agent-run-v1",
      "requestSchemaVersion": "agent-analysis-request-v1",
      "agentRuntimeVersion": "bounded-tool-agent-v1",
      "inputFingerprintVersion": "sha256-task-scoped-length-prefixed-v1",
      "inputFingerprint": "6d4c96fb440708f7e456e0598d60ea1b940fd2441362c792961f8bd89345b5d1",
      "chatProviderCalls": 3,
      "chatProviderDurationMs": 840,
      "toolDurationMs": 41,
      "totalDurationMs": 1120,
      "contextCharsSent": 9842
    },
    "modelUsage": {
      "promptTokens": 100,
      "completionTokens": 30,
      "totalTokens": 130,
      "providerReported": true,
      "estimatedCostUsd": "0.00027000",
      "pricingVersion": "provider-price-2026-08-01"
    },
    "toolTrace": []
  },
  "createdAt": "2026-08-13T09:30:00"
}
```

真实契约由 Pydantic、Java Agent 边界校验、显式 API wire DTO/OpenAPI 快照、生成的 TypeScript 类型和前端 Zod 共同约束。历史 provenance 可缺失 trace/run/cost 新增字段并在读取时规范化为 `null`；其他必填结构化字段缺失时，API 拒绝输出损坏的存储 JSON。

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
- 若后续引入新报告 schema version，为 V3 增加独立 DTO 与解析分支，不在 V2 中静默加入无法被旧客户端理解的语义。

## 6. 数据模型

当前核心实体：

- `Resume`：owner、文件名、单份原始提取文本和创建时间；V9 已删除过去与原文重复的结构化摘要列。
- `JobDescription`：JD 内容、技能标签。
- `AnalysisTask`：简历 ID、JD ID、状态、attempts、失败码、失败消息、预留的下一次重试时间、开始/完成/创建/更新时间。
- `MatchReport`：任务 ID、匹配分数、`report_schema_version`、结构化报告 JSON、`analysis-run-v1` provenance JSON、Markdown 兼容正文和创建时间。
- `AnalysisOutboxEvent`：事件类型、聚合类型、聚合 ID、payload、投递状态（`PENDING`/`PROCESSING`/`PUBLISHED`/`FAILED`/`DEAD`）、attempts、下一次投递时间、最后错误、创建/发布时间。
- `AnalysisSubmissionIdempotencyRecord`：只保存 SHA-256 key hash、请求指纹、关联 task ID 和时间戳，用唯一约束协调并发重复提交。

目标实体增强：

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
- 文件摄取与隐私阶段加入 PDF/DOCX magic-byte 匹配、DOCX Word 部件/解压配额、PDF 页数限制、DOCX 表格抽取、重复正文列清理，以及所有 AI 引擎共享的 provider-bound PII 脱敏、分类指标、100 组反事实输入一致性和 telemetry 正文 canary 门禁。
- 前端产品化阶段加入原子提交、列表/汇总接口、React 工作台、路由级 lazy loading、稳定重试幂等 key、Nginx 同源代理、确定性 Playwright 场景和真实 Compose 浏览器验收。
- 可靠性收口加入提交 key hash/request fingerprint、409 冲突语义、陈旧 `RUNNING` 恢复、attempt completion fence、outbox 最大尝试/`DEAD`、失败消息清理及相关指标。
- Agent 化阶段加入 Python/FastAPI sidecar、`AnalysisEngine` 可替换端口、三工具白名单、有界循环、completion/total/context 预算、逐条 claim 引用、检索空结果、内部 token、Agent 指标、60-query 检索基线和版本化 120-case live Agent 评测集（含 60 条安全对抗用例）。
- Git 仓库根目录已配置 push/pull request GitHub Actions，覆盖 Java fast/集成、Python、前端质量/构建和浏览器 E2E；发布前远程运行已验证 5 个 jobs 全绿，浏览器 job 包含确定性 Playwright 与真实 Compose 全栈流程。

待实现：

- 当前 B+ 工程化重构计划内无剩余必做项；后续增强应作为新的阶段或需求单独设计。

## 9. 架构决策

- 保持 Spring Boot 为业务主系统，只拆出无状态 Agent runtime；MySQL、任务状态和重试仍由 Java 负责。
- MySQL 保持事实来源，Redis 和 RabbitMQ 都是派生或传输组件。
- 单份简历索引保留在进程内；默认 hashing 支持无凭据回归，显式 hybrid 通过 provider embedding 增强召回。只有同 qrels 的真实 A/B 达标后才考虑切换默认，跨人才库场景再评估外部向量库。
- AI provider 通过 OpenAI-compatible Tool Calling client 抽象，避免 Agent 依赖具体供应商 SDK。
- 模型负责选择受限动作并提出 requirement status/解释；权限、顺序、资源预算、同 requirement 引用来源、否定/词项规则验证、gap 派生、score 公式和最终接纳由确定性代码负责。规则 verifier 尚未覆盖完整语义蕴含，建议和面试题仍是自由文本，因此不把它表述为“事实真实性保证”。
- 前端不持有 API token；认证注入只发生在受控代理边界。
- 确定性浏览器测试负责 UI 分支与响应式回归，真实全栈验收负责跨进程契约，两者不相互替代。
- 每个阶段先保护行为契约，再做结构迁移。
