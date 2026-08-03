# AI Resume Match Operations Runbook

本文档记录 `ai-resume-match` 当前可执行的本地运行、检查和排障流程。Compose 可启动 frontend、app、agent、MySQL、Redis、RabbitMQ，并提供健康检查、request/correlation ID、结构化任务日志和 Micrometer metrics。

## 1. 完整 Docker Compose 启动

进入项目目录：

```powershell
cd ai-resume-match
```

准备本地环境文件：

```powershell
Copy-Item .env.example .env
notepad .env
```

确认 `.env` 中 `AI_API_KEY`、`API_TOKEN`、`AGENT_SERVICE_TOKEN` 和依赖服务密码都已设置为本地开发值。默认 `ANALYSIS_ENGINE=agent`。不要提交 `.env`。

启动完整运行时：

```powershell
docker compose up -d --build
```

检查服务状态：

```powershell
docker compose ps
curl.exe -i http://localhost:3000/frontend-health
curl.exe -i http://localhost:8080/actuator/health/readiness
curl.exe -i http://localhost:8000/health
```

应用 readiness 使用 Spring `readinessState`，用于判断 app 容器是否可接流量；MySQL、Redis、RabbitMQ 的依赖健康以 Compose healthcheck 和 app 启动期连接/迁移结果为准。Redis 是 cache-aside，不作为 readiness 阻断项。

默认本地端口：

- 产品前端：`http://localhost:3000`，由 Nginx 提供。
- App：`http://localhost:8080`。
- Agent：`http://localhost:8000`；分析接口需要 `X-Agent-Token`，只有 `/health` 公开。
- MySQL：`localhost:3306`，database 默认 `ai_resume_match`，用户/密码来自 `.env`。
- Redis：`localhost:6379`，密码来自 `.env`。
- RabbitMQ：`localhost:5672`，用户名/密码来自 `.env`。
- RabbitMQ 管理页：`http://localhost:15672`。

这些 host 端口均可通过 `.env` 中的 `FRONTEND_PORT`、`APP_PORT`、`AGENT_SERVICE_PORT`、`MYSQL_PORT`、`REDIS_PORT`、`RABBITMQ_AMQP_PORT`、`RABBITMQ_MANAGEMENT_PORT` 覆盖。app 等待 agent 健康，frontend 等待 app readiness；`/frontend-health` 用于检查 Nginx 静态服务。

浏览器不持有 API token。Nginx 从容器环境读取 `API_TOKEN`，在代理 `/api` 时写入 `X-API-Token`；不要把 token 改成 `VITE_*` 变量或写入静态文件。

## 2. 本机 Maven 开发启动

如需本机运行 Spring Boot 和 Agent，只启动数据依赖：

```powershell
Copy-Item .env.example .env
notepad .env
docker compose up -d mysql redis rabbitmq
$env:AI_API_KEY="replace-with-local-dev-key"
$env:AGENT_SERVICE_TOKEN="dev-agent-token"
Set-Location agent-service
py -3.13 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\uvicorn.exe agent_service.main:app --port 8000
```

另开一个位于项目根目录的终端：

```powershell
$env:SPRING_PROFILES_ACTIVE="dev"
$env:API_TOKEN="dev-token"
$env:ANALYSIS_ENGINE="agent"
$env:AGENT_SERVICE_TOKEN="dev-agent-token"
mvn spring-boot:run
```

`dev` profile 默认连接 `localhost` 依赖；`docker` profile 使用 Compose 服务名；`prod` profile 不包含本地默认凭据。设置 `ANALYSIS_ENGINE=legacy` 可绕过 Agent，验证原有 Java RAG 回退链路。

另开终端启动前端开发服务：

```powershell
npm --prefix frontend ci
$env:API_PROXY_TARGET="http://localhost:8080"
$env:API_TOKEN="dev-token"
npm --prefix frontend run dev
```

## 3. 停止与清理

停止完整运行时：

```powershell
docker compose down
```

如需清空本地持久化数据，先确认不需要保留测试数据，再执行：

```powershell
docker compose down -v
```

## 4. 快速验证

运行 fast tests：

```powershell
mvn test
Set-Location agent-service
.\.venv\Scripts\python.exe -m pytest
Set-Location ..
```

在 Agent 已启动且已配置模型后运行合成评测：

```powershell
Set-Location agent-service
$env:AGENT_SERVICE_TOKEN="dev-agent-token"
.\.venv\Scripts\python.exe evals\run_evals.py --output eval-results\latest.json
Set-Location ..
```

评测集不含真实个人信息，覆盖 grounding、工具顺序、提示注入抵抗、分数区间、step 和延迟边界。输出只记录 case ID、检查结果、分数、延迟、step 和 token usage，不复制简历/JD 正文。

运行集成和端到端测试。Docker Desktop 需要处于运行状态；Windows 上如需显式使用 Docker Desktop Linux engine，可先设置 `DOCKER_HOST`：

```powershell
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'
mvn verify
```

验证 Compose 配置：

```powershell
docker compose --env-file .env.example config
```

构建应用镜像：

```powershell
docker build -t ai-resume-match:local .
docker build -t ai-resume-match-frontend:local frontend
```

当前 `mvn verify` 会通过 Testcontainers 验证 MySQL Flyway migration、MySQL+RabbitMQ outbox 生命周期、publisher confirm/return 行为、Redis cache-aside，以及 mock AI HTTP server 驱动的 PDF/DOCX 端到端分析流。Docker Desktop 未运行时，这些容器测试会跳过或无法执行，不能用 fast tests 代替其结论。

前端和完整产品流验证：

```powershell
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend run test
npm --prefix frontend run build
npm --prefix frontend run test:e2e
npm --prefix frontend run test:e2e:full-stack
```

`test:e2e` 使用浏览器路由 mock，不访问外部网络。`test:e2e:full-stack` 使用独立 Compose 项目和 mock AI Tool Calling 服务，验证真实上传、outbox、worker、Agent 三工具链路、91 分报告及历史回查，结束后自动 `down -v`。

## 5. 基础业务 smoke flow

浏览器 smoke flow：打开 `http://localhost:3000`，确认顶部显示“API 已连接”，然后依次检查“新建分析”、任务进度、报告和“分析记录”。

检查 API token 拦截：

```powershell
curl.exe -i http://localhost:8080/api/analysis/1
```

预期：没有 `X-API-Token` 时返回 `401`，body 为结构化错误。

上传简历：

```powershell
curl.exe -i `
  -H "X-API-Token: dev-token" `
  -F "file=@.\sample-resume.pdf" `
  http://localhost:8080/api/resumes
```

创建 JD：

```powershell
curl.exe -i `
  -H "X-API-Token: dev-token" `
  -H "Content-Type: application/json" `
  -d "{\"title\":\"高级后端工程师\",\"content\":\"Java Spring Boot Redis RabbitMQ\"}" `
  http://localhost:8080/api/jobs
```

创建分析任务：

```powershell
curl.exe -i `
  -H "X-API-Token: dev-token" `
  -H "Content-Type: application/json" `
  -d "{\"resumeId\":1,\"jobDescriptionId\":1}" `
  http://localhost:8080/api/analysis
```

浏览器默认使用原子提交接口，一次请求创建简历、JD、任务和 outbox：

```powershell
curl.exe -i `
  -H "X-API-Token: dev-token" `
  -F "file=@.\sample-resume.pdf" `
  -F "jobTitle=高级后端工程师" `
  -F "jobContent=Java Spring Boot Redis RabbitMQ" `
  http://localhost:8080/api/analysis-submissions
```

历史与总览：

```powershell
curl.exe -i -H "X-API-Token: dev-token" "http://localhost:8080/api/analysis?page=0&size=20"
curl.exe -i -H "X-API-Token: dev-token" http://localhost:8080/api/analysis/summary
```

查询任务状态：

```powershell
curl.exe -i `
  -H "X-API-Token: dev-token" `
  http://localhost:8080/api/analysis/1
```

查询报告：

```powershell
curl.exe -i `
  -H "X-API-Token: dev-token" `
  http://localhost:8080/api/analysis/1/report
```

手动重试可重试失败任务：

```powershell
curl.exe -i `
  -X POST `
  -H "X-API-Token: dev-token" `
  http://localhost:8080/api/analysis/1/retry
```

预期：只有 `FAILED_RETRYABLE` 任务可以被重置为 `PENDING` 并重新投递；其他状态返回 `400 BAD_REQUEST`。

自动重试：`FAILED_RETRYABLE` 任务如果带有已到期的 `nextRetryAt`，调度器会自动重置为 `PENDING` 并写入 outbox 重新投递。尝试次数达到 `maxAttempts` 后会转为 `FAILED_FINAL`，不再自动重试。

## 6. 常见问题

### 6.1 返回 401

检查：

- 请求是否带 `X-API-Token`。
- 请求头值是否等于 `.env` 或当前终端中的 `API_TOKEN`。
- 应用是否在设置环境变量之后启动。

### 6.2 上传简历失败

检查：

- 文件扩展名是否为 `.pdf` 或 `.docx`。
- 文件大小是否不超过 `resume.upload.max-file-size`，默认 `5MB`。
- 文档提取后的正文是否超过 200,000 个 Unicode code point；超限会在写库和投递前拒绝。
- PDF/DOCX 是否包含可提取文本。
- 当前不支持图片扫描件 OCR。

### 6.3 任务一直 PENDING

可能原因：

- RabbitMQ 未启动或未健康。
- outbox 事件待发布、发布中，或因 RabbitMQ 不可达、不可路由、confirm 失败后等待下一次重试。
- worker 未正常监听队列。

检查：

```powershell
docker compose ps rabbitmq
docker compose logs app --tail 100
```

打开 RabbitMQ 管理页检查队列是否存在消息堆积。

检查 outbox 积压：

```powershell
docker compose exec mysql mysql -u$env:MYSQL_USER -p$env:MYSQL_PASSWORD $env:MYSQL_DATABASE -e "select id,event_type,aggregate_id,status,attempt_count,next_attempt_at,last_error from analysis_outbox order by id desc limit 20;"
```

预期：`PENDING`、due 的 `FAILED`、以及已过期的 `PROCESSING` outbox 事件会由 `AnalysisOutboxPublisher` 自动认领并发布；`PROCESSING` 通常只会短暂出现，`last_error` 可用于定位 RabbitMQ 连接、不可路由或消息转换问题。

每次实际发布失败都会增加 `attempt_count`；停机中断不会消耗次数，并会终止当前批次。默认达到 `ANALYSIS_OUTBOX_MAX_ATTEMPTS=10` 后，事件会进入 `DEAD`、清空 `next_attempt_at`，此后不会再被任何 publisher 实例认领。每轮发布前还会用 guarded update 扫描升级前遗留或调低上限后已经耗尽的非终态事件，将其收敛到 `DEAD`。batch size、最大次数、重试间隔和 confirm timeout 都必须为正数，否则应用拒绝启动。

`DEAD` 表示需要人工介入：事件会保留用于审计，`last_error` 会清理控制字符并安全截断。若关联任务仍为 `PENDING`，同一事务会把它置为 `FAILED_RETRYABLE / DELIVERY_FAILED`，且 `next_retry_at=null`，因此自动 retry scheduler 不会立即重试；已经 `RUNNING`、`SUCCESS` 或进入其他状态的任务不会被覆盖。修复 broker、路由或凭据后，在原任务详情中点击“重新分析”（或调用现有 retry API）会创建一条新的 outbox 事件。不要直接把旧 `DEAD` 事件改回 `PENDING`。建议对 `analysis.outbox.backlog{status="dead"} > 0` 和 `analysis.outbox.events{outcome="dead"}` 告警。

### 6.4 Redis 或报告缓存异常

Redis 是缓存，不是事实来源。Redis 异常不应导致报告数据丢失。

检查：

```powershell
docker compose ps redis
docker compose exec redis redis-cli -a $env:REDIS_PASSWORD ping
```

如 Redis 不可用，优先确认 MySQL 中报告是否存在。

### 6.5 RabbitMQ 登录失败

检查 `.env` 中：

- `RABBITMQ_DEFAULT_USER`
- `RABBITMQ_DEFAULT_PASS`

RabbitMQ 管理页：`http://localhost:15672`。

### 6.6 任务失败

可能原因：

- AI API key 无效。
- AI endpoint 不可达或超时。
- AI 返回内容无法解析匹配分数。
- Agent 不可达、内部 token 不匹配或模型没有在预算内提交结构化报告。
- 简历或 JD 源数据缺失。

检查：

- `.env` 或当前终端中的 `AI_API_KEY` 是否设置。
- `AI_ENDPOINT` 和 `AI_MODEL` 是否符合当前供应商。
- `ANALYSIS_ENGINE` 是否为预期值；Agent 模式下检查 `AGENT_SERVICE_TOKEN` 两端是否一致。
- `curl.exe -i http://localhost:8000/health` 和 `docker compose logs agent --tail 100` 是否正常。
- 应用日志中按 `taskId` 搜索失败记录。

当前行为：

- Phase 2 已提供 `failureCode`、`failureMessage`、attempt 元数据和手动 retry endpoint。
- Phase 3 已提供 `nextRetryAt`、自动重试调度和带 publisher confirm/return 的 outbox 重投递。
- `FAILED_FINAL` 是终态；如需重新分析，应创建新任务。
- Agent HTTP 5xx、网络错误、408 和 429 按 retryable 处理；其他 4xx、越界响应和协议失败按 final 处理，避免无效请求无限重试。

### 6.7 Agent 调用了错误工具或未完成

Agent runtime 会确定性拒绝以下情况：未知工具、未先读取 JD 就检索、未检索就提交、参数 schema 不合法、伪造 evidence ID、超过 step/工具/协议错误预算，以及用普通文本结束任务。

检查：

```powershell
docker compose logs agent --tail 100
curl.exe -i http://localhost:8080/actuator/metrics/agent.calls
curl.exe -i http://localhost:8080/actuator/metrics/agent.call.duration
```

日志只应包含任务 ID、模型、step、耗时和错误类别，不应出现简历/JD、prompt、工具参数或模型原文。若模型经常触发上限，先用 `agent-service/evals` 复现并改进 system prompt/模型选择；不要简单取消边界。

### 6.8 前端无法访问或显示 API 暂不可用

检查：

```powershell
docker compose ps frontend app
docker compose logs frontend --tail 100
curl.exe -i http://localhost:3000/frontend-health
curl.exe -i http://localhost:3000/backend-health
```

- frontend 不健康：检查运行时 `API_TOKEN` 是否存在且不含 CR/LF，并执行 `nginx -t`。
- `/frontend-health` 正常但 `/backend-health` 失败：检查 app readiness 和依赖容器。
- 浏览器刷新子路由返回 404：确认使用仓库提供的 Nginx template，SPA fallback 应回到 `index.html`。

## 7. 数据与安全

- 不要把真实简历、JD、AI prompt 或 AI 原始输出贴入 issue、commit message、日志样例或文档。
- 当前服务会把简历/JD 相关内容发送给配置的 AI provider。
- 前端不得把简历、JD、token 写入 localStorage/sessionStorage；页面卸载会取消仍在进行的敏感上传请求。
- Markdown 报告不加载远程图片，避免报告内容触发第三方请求。
- `.env`、真实 API key、真实 token 不得提交。
- `.env.example` 只放示例值，不放真实密钥。
- 生产环境必须使用 `prod` profile，并通过环境变量注入真实配置。

### 7.1 Flyway 发布与前向恢复

- 当前 Compose 交付按单实例维护窗口升级，不承诺新旧应用版本滚动共存。
- 发布前备份 MySQL，并在维护窗口停止旧 app；先让新 app 启动完成 Flyway migration，再开放 frontend 流量。
- migration 只做前向、可重复验证的 schema 演进。已在共享环境执行的 migration 不修改 checksum，也不依赖手工回滚 SQL。
- 如果 migration 失败，保持 frontend/app 不接流量，修复原因后新增前向 migration 或恢复发布前备份；不要用 `flyway repair` 掩盖未知差异。
- 本次岗位标题字段为 nullable，旧客户端与旧数据可继续读取，应用和 schema 应在同一维护窗口发布。

## 8. 可观测性检查

请求定位：

```powershell
curl.exe -i `
  -H "X-API-Token: dev-token" `
  -H "X-Request-Id: local-debug-1" `
  -H "X-Correlation-Id: local-correlation-1" `
  http://localhost:8080/api/analysis/1
```

预期：响应 header 保留 `X-Request-Id` 和 `X-Correlation-Id`；错误响应 body 包含 `requestId`。缺失或非法 header 会由服务生成安全 UUID。

日志中可按 `requestId`、`correlationId`、`taskId` 和 `event=analysis_task_*` 搜索任务生命周期。任务日志只记录 ID、状态和失败分类，不记录简历原文、JD 原文、prompt 或 AI 原始响应。

指标检查：

```powershell
curl.exe -i http://localhost:8080/actuator/metrics
curl.exe -i http://localhost:8080/actuator/metrics/analysis.tasks.created
curl.exe -i http://localhost:8080/actuator/metrics/analysis.tasks.succeeded
curl.exe -i http://localhost:8080/actuator/metrics/analysis.tasks.failed
curl.exe -i http://localhost:8080/actuator/metrics/analysis.worker.duration
curl.exe -i http://localhost:8080/actuator/metrics/agent.calls
curl.exe -i http://localhost:8080/actuator/metrics/agent.call.duration
curl.exe -i http://localhost:8080/actuator/metrics/ai.calls
curl.exe -i http://localhost:8080/actuator/metrics/ai.call.duration
curl.exe -i http://localhost:8080/actuator/metrics/report.cache.requests
curl.exe -i http://localhost:8080/actuator/metrics/report.cache.writes
curl.exe -i http://localhost:8080/actuator/metrics/analysis.outbox.events
curl.exe -i http://localhost:8080/actuator/metrics/analysis.outbox.backlog
```

`analysis.outbox.backlog` 的 `status` 包括 `pending`、`failed`、`processing` 和终态 `dead`；`analysis.outbox.events` 的 `outcome=dead` 记录本进程观察到的终态转换次数。Gauge 是数据库当前事实，counter 适合观察转换速率。

RabbitMQ 队列深度仍通过 RabbitMQ 管理页或命令检查：

```powershell
docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
```
