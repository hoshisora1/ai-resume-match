# AI Resume Match Operations Runbook

本文档记录 `ai-resume-match` 当前可执行的本地运行、检查和排障流程。Compose 可启动 frontend、app、agent、MySQL、Redis、RabbitMQ，并提供健康检查、request/correlation ID、结构化任务日志和 Micrometer metrics；可选 overlay 还能启动本机 Prometheus/Grafana/Tempo 与 W3C/OTLP 分布式追踪。

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

应用 readiness 同时包含 Spring `readinessState` 与 MySQL `db`；数据库不可用时返回非健康状态。Redis、RabbitMQ 仍有独立 Compose healthcheck，其中 Redis 是 cache-aside，不作为 readiness 阻断项。

默认本地端口：

- 产品前端：`http://localhost:3000`，由 Nginx 提供。
- App：`http://localhost:8080`。
- Agent：`http://localhost:8000`；分析接口需要 `X-Agent-Token`，`/health` 与供内部 Prometheus 抓取的 `/metrics` 不鉴权。共享部署不得把 Agent 管理面暴露公网。
- MySQL：`localhost:3306`，database 默认 `ai_resume_match`，用户/密码来自 `.env`。
- Redis：`localhost:6379`，密码来自 `.env`。
- RabbitMQ：`localhost:5672`，用户名/密码来自 `.env`。
- RabbitMQ 管理页：`http://localhost:15672`。

这些 host 端口均可通过 `.env` 中的 `FRONTEND_PORT`、`APP_PORT`、`AGENT_SERVICE_PORT`、`MYSQL_PORT`、`REDIS_PORT`、`RABBITMQ_AMQP_PORT`、`RABBITMQ_MANAGEMENT_PORT` 覆盖。app 等待 agent 健康，frontend 等待 app readiness；`/frontend-health` 用于检查 Nginx 静态服务。

如需启动本地指标栈，使用相同 `.env` 叠加 observability 文件：

```powershell
docker compose --env-file .env -f docker-compose.yml -f docker-compose.observability.yml up -d --build
curl.exe -sS http://localhost:9090/-/ready
curl.exe -sS http://localhost:9090/api/v1/targets
curl.exe -sS http://localhost:3200/ready
```

Prometheus 默认是 `http://localhost:9090`，Grafana 默认是 `http://localhost:3001`，Tempo 查询 API 默认是 `http://localhost:3200`；端口分别用 `PROMETHEUS_PORT`、`GRAFANA_PORT`、`TEMPO_PORT` 覆盖，全部只绑定 `127.0.0.1`。Grafana 预置 `AI Resume Match — Operations` dashboard、Prometheus 和 Tempo 数据源。overlay 会把 Java/Agent tracing 开关设为 `true` 并导出到 Compose 内部 `tempo:4318`。启动共享环境前必须修改 `GRAFANA_ADMIN_PASSWORD`；当前没有 Alertmanager，规则进入 firing 只会出现在 Prometheus/Grafana，不会发送外部通知。

浏览器不持有 API token。Nginx 从容器环境读取 `API_TOKEN`，在代理 `/api` 时写入 `X-API-Token`；不要把 token 改成 `VITE_*` 变量或写入静态文件。Spring 会另行签发 `HttpOnly` 匿名会话 Cookie，Nginx 原样转发 `Set-Cookie/Cookie`；数据库只保存其 session id 的 SHA-256。清理浏览器 Cookie 会生成新身份，旧匿名数据不可恢复。

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

`dev` profile 默认连接 `localhost` 依赖；`docker` profile 使用 Compose 服务名；`prod` profile 不包含本地默认凭据。设置 `ANALYSIS_ENGINE=legacy` 可显式绕过 Agent，用于原有 Java RAG 基线或兼容验证；系统不会在 Agent 失败时自动切换。

默认时间预算为 provider 单次调用 45 秒、Agent 整次分析 50 秒、Java read timeout 60 秒。应始终保持 `AGENT_MODEL_TIMEOUT_SECONDS < AGENT_ANALYSIS_TIMEOUT_SECONDS < AGENT_READ_TIMEOUT`，避免 Java 已重试而 Python 仍继续消耗模型额度。

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
.\.venv\Scripts\python.exe evals\run_evals.py `
  --repeat 3 `
  --min-pass-rate 0.95 `
  --max-score-stddev 3 `
  --summary-only `
  --output eval-results\latest.json
Set-Location ..
```

评测集不含真实个人信息，`agent-live-eval-v4` 包含 120 条合成 case，覆盖 grounding、工具顺序、空证据、否定表达、长文/格式噪声、时效边界、缩写/同义词、伪造 evidence ID、协议形状输入、分数区间、step 和延迟边界。50 条简历/JD 直接注入用例可用 `--tag resume-injection --tag jd-injection` 选择；全部 60 条安全对抗用例使用 `--tag security`。多个 tag 是 OR 关系。输出绑定数据集哈希与 model/prompt/retriever/verifier 版本，记录检查结果、分数稳定性、p50/p95、step、token usage、可用时的成本估算覆盖率/总额/pricing versions，以及开启 tracing 时每次运行的 trace ID；不复制简历/JD 正文或 provider 错误消息。用例数量只代表覆盖范围，不能替代真实 provider 运行结果。

成本估算默认关闭。启用前从同一份、带生效日期的 provider 价格来源填写以下三项，禁止只改一项：

```powershell
$env:AGENT_MODEL_PRICING_VERSION="provider-price-YYYY-MM-DD"
$env:AGENT_MODEL_INPUT_COST_USD_PER_MILLION_TOKENS="<provider-input-price>"
$env:AGENT_MODEL_OUTPUT_COST_USD_PER_MILLION_TOKENS="<provider-output-price>"
```

启动日志和 `/health` 不暴露单价，只报告 `costEstimation=enabled|disabled`。分析响应只有在 chat provider 返回完整 prompt/completion/total usage 时才包含 `estimatedCostUsd` 与 `pricingVersion`；缺失时两个字段必须同时为 `null`。修改价格时创建新 pricing version，不要覆盖旧报告含义。该估算只按配置的平面输入/输出 token 单价计算，不包含 embedding、缓存 token 阶梯价、存储、网络、税费或其他计费项；Prometheus 美元 counter 是浮点运维趋势，单次响应/持久化 provenance 中的 Decimal 字符串才是权威工程估算，两者都不能替代 provider 账单。

默认 `RETRIEVER_MODE=hashing`，不需要 embedding provider。评估 hybrid 时必须显式配置 `EMBEDDING_ENDPOINT`、固定的 `EMBEDDING_MODEL` 以及 `EMBEDDING_API_KEY`（留空则进程内回退读取 `AI_API_KEY`），并先与已提交 hashing baseline 做同数据集对比：

```powershell
Set-Location agent-service
$env:RETRIEVER_MODE="hybrid"
$env:EMBEDDING_ENDPOINT="https://api.openai.com/v1/embeddings"
$env:EMBEDDING_MODEL="text-embedding-3-small"
.\.venv\Scripts\python.exe evals\run_retrieval_eval.py `
  --retriever hybrid `
  --baseline evals\baselines\hashing-v1.json `
  --min-recall 0.90 `
  --min-mrr 0.80 `
  --max-false-positive-rate 0.05 `
  --output eval-results\hybrid.json
Set-Location ..
```

不要把 fake embedding 单测或未保存的终端输出写成语义检索指标。hybrid 无自动 hashing fallback；provider 故障会按 Agent 失败契约返回，由 Java 重试边界处理，避免一次任务内检索语义悄然变化。

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

当前 `mvn verify` 会通过 Testcontainers 验证 MySQL Flyway migration、MySQL+RabbitMQ outbox 生命周期、publisher confirm/return、短事务并发认领、ACK 后终止留下的租约过期重领、终态 outbox/幂等记录的安全清理、陈旧 `RUNNING` 恢复、Agent HTTP 读超时、Redis pause 下的有界回源/恢复，以及 mock AI HTTP server 驱动的 PDF/DOCX 端到端分析流。Docker Desktop 未运行时，这些容器测试会跳过或无法执行，不能用 fast tests 代替其结论。

前端和完整产品流验证：

```powershell
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend run test
npm --prefix frontend run build
npm --prefix frontend run test:e2e
npm --prefix frontend run test:e2e:full-stack
```

`test:e2e` 使用浏览器路由 mock，不访问外部网络。`test:e2e:full-stack` 使用独立 Compose 项目、真实 Python Agent 和本地确定性 chat provider，验证真实上传、outbox、worker、动态三工具链路、V2 确定性计分、证据/溯源及历史回查。基础设施不映射宿主端口，前端动态绑定 `127.0.0.1` 空闲端口；失败时输出容器状态和关键日志，结束后自动 `down -v`。

无付费产品演示使用 `docker-compose.demo.yml` overlay。它保留数据 volume，直到显式执行带同一组 `-f` 参数的 `down -v`；默认仅开放 `127.0.0.1:3000`，可通过 `DEMO_PORT` 覆盖。演示数据必须使用页面内的合成示例，不要上传真实候选人资料。

## 5. 基础业务 smoke flow

浏览器 smoke flow：打开 `http://localhost:3000`，确认顶部显示“API 已连接”，然后依次检查“新建分析”、任务进度、报告和“分析记录”。

命令行多步 flow 必须复用同一匿名会话：

```powershell
$sessionJar = Join-Path $PWD ".demo-session.cookies"
```

检查 API token 拦截：

```powershell
curl.exe -i http://localhost:8080/api/analysis/1
```

预期：没有 `X-API-Token` 时返回 `401`，body 为结构化错误。

上传简历：

```powershell
curl.exe -i `
  -c $sessionJar -b $sessionJar `
  -H "X-API-Token: dev-token" `
  -F "file=@.\sample-resume.pdf" `
  http://localhost:8080/api/resumes
```

创建 JD：

```powershell
curl.exe -i `
  -c $sessionJar -b $sessionJar `
  -H "X-API-Token: dev-token" `
  -H "Content-Type: application/json" `
  -d "{\"title\":\"高级后端工程师\",\"content\":\"Java Spring Boot Redis RabbitMQ\"}" `
  http://localhost:8080/api/jobs
```

创建分析任务：

```powershell
curl.exe -i `
  -c $sessionJar -b $sessionJar `
  -H "X-API-Token: dev-token" `
  -H "Content-Type: application/json" `
  -d "{\"resumeId\":1,\"jobDescriptionId\":1}" `
  http://localhost:8080/api/analysis
```

浏览器默认使用原子提交接口，一次请求创建简历、JD、任务和 outbox：

```powershell
curl.exe -i `
  -c $sessionJar -b $sessionJar `
  -H "X-API-Token: dev-token" `
  -F "file=@.\sample-resume.pdf" `
  -F "jobTitle=高级后端工程师" `
  -F "jobContent=Java Spring Boot Redis RabbitMQ" `
  http://localhost:8080/api/analysis-submissions
```

正常响应应为 `202 Accepted`，包含 `Location: /api/analysis/{taskId}`。认证、校验、资源不存在和幂等冲突等已处理错误使用 `Content-Type: application/problem+json`；排障时同时记录响应中的 `requestId` 与响应头 `X-Request-Id`，不要复制简历或 JD 正文到工单。

历史与总览：

```powershell
curl.exe -i -c $sessionJar -b $sessionJar -H "X-API-Token: dev-token" "http://localhost:8080/api/analysis?page=0&size=20"
curl.exe -i -c $sessionJar -b $sessionJar -H "X-API-Token: dev-token" http://localhost:8080/api/analysis/summary
```

查询任务状态：

```powershell
curl.exe -i `
  -c $sessionJar -b $sessionJar `
  -H "X-API-Token: dev-token" `
  http://localhost:8080/api/analysis/1
```

查询报告：

```powershell
curl.exe -i `
  -c $sessionJar -b $sessionJar `
  -H "X-API-Token: dev-token" `
  http://localhost:8080/api/analysis/1/report
```

手动重试可重试失败任务：

```powershell
curl.exe -i `
  -c $sessionJar -b $sessionJar `
  -X POST `
  -H "X-API-Token: dev-token" `
  http://localhost:8080/api/analysis/1/retry
```

预期：只有 `FAILED_RETRYABLE` 任务可以被重置为 `PENDING` 并重新投递；其他状态返回 `400 BAD_REQUEST`。

创建/原子提交/重试超过会话配额时返回 `429 RATE_LIMIT_EXCEEDED`，`Retry-After` 表示至少等待秒数；此时不会创建 task/outbox 或调用模型。Redis 无法执行配额 Lua 时这些付费入口返回 `503 RATE_LIMIT_UNAVAILABLE`，但历史、任务和报告读取仍可工作。

自动重试：`FAILED_RETRYABLE` 任务如果带有已到期的 `nextRetryAt`，调度器会自动重置为 `PENDING` 并写入 outbox 重新投递。运行失败从 `ANALYSIS_RETRY_DELAY=1m` 开始按 attempt 翻倍，加入 `ANALYSIS_RETRY_JITTER_RATIO=0.2` 的对称扰动，并受 `ANALYSIS_RETRY_MAX_DELAY=15m` 限制。Agent 返回正数 `retryAfterSeconds` 时，它是本次最短等待时间，但同样会被本地上限截断；零、负数和缺失值不会覆盖本地策略。尝试次数达到 `maxAttempts` 后会转为 `FAILED_FINAL`，不再自动重试。

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
- 文件真实签名是否与扩展名一致；改后缀的文本/ZIP/PDF 会在解析前拒绝。
- PDF 是否超过 `RESUME_MAX_PDF_PAGES=50`。
- DOCX 是否包含 `[Content_Types].xml` 与 `word/document.xml`，或超过 `RESUME_MAX_DOCX_ENTRIES=512`、单 entry `10MB`、累计解压 `20MB` 的默认边界。
- 文档提取后的正文是否超过 200,000 个 Unicode code point；超限会在写库和投递前拒绝。
- PDF/DOCX 是否包含可提取文本。
- 当前不支持图片扫描件 OCR。

DOCX 普通段落和表格按正文顺序抽取，行内空白会归一化并保留段落边界。不要通过提高 DOCX 解压上限来接收异常压缩包；确有超大可信文件需求时，应同时评估 JVM heap、请求并发和解析耗时。

模型数据边界：MySQL 中只保留一份 `raw_text`，并受 owner、主动删除和默认 30 天 retention 管理；V9 已删除过去与它重复的 `structured_summary`。worker 构造模型输入后、调用任何 AI 引擎前执行规则脱敏。当前覆盖邮箱、常见中外电话、中国居民身份证号，以及带 `姓名/Name`、`地址/Address`、性别、年龄、出生日期、婚姻状态标签的值。脱敏日志不包含正文或命中值；用 `analysis.model.input.redactions{type}` 查看分类计数。该规则不能替代 NER、DLP 或人工检查，公开演示仍应使用合成数据。

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
docker compose exec mysql mysql -u$env:MYSQL_USER -p$env:MYSQL_PASSWORD $env:MYSQL_DATABASE -e "select id,event_type,aggregate_id,status,attempt_count,next_attempt_at,lease_token,lease_until,terminal_at,last_error from analysis_outbox order by id desc limit 20;"
```

预期：`PENDING`、due 的 `FAILED`、以及 `lease_until` 已过期的 `PROCESSING` outbox 事件会由 `AnalysisOutboxPublisher` 自动认领并发布。认领短事务提交后才在事务外等待 Rabbit confirm，结果短事务以 `lease_token` fencing；因此 `PROCESSING` 在网络调用期间可见，但旧 token 的迟到结果不能覆盖新租约。`last_error` 可用于定位 RabbitMQ 连接、不可路由或消息转换问题。

每次实际发布失败都会增加 `attempt_count`；停机中断会释放当前租约、不会消耗次数，并会终止当前批次。进程在 ACK 后直接终止时租约会保留，达到 `lease_until` 后自动重领，可能产生重复消息。失败重试从 `ANALYSIS_OUTBOX_RETRY_DELAY=30s` 开始按尝试次数翻倍，加入 `ANALYSIS_OUTBOX_RETRY_JITTER_RATIO=0.2` 的随机扰动，并受 `ANALYSIS_OUTBOX_RETRY_MAX_DELAY=15m` 限制，避免故障恢复时所有实例同时重试。默认达到 `ANALYSIS_OUTBOX_MAX_ATTEMPTS=10` 后，事件会进入 `DEAD`、写入 `terminal_at` 并清空 retry/lease 字段，此后不会再被 publisher 认领。每轮发布前还会用 guarded update 扫描升级前遗留或调低上限后已经耗尽的非终态事件，将其收敛到 `DEAD`。batch size、最大次数、各退避时长、confirm timeout 和 lease duration 都必须为正数，jitter 必须位于 `0..1`，且 lease duration 必须大于 confirm timeout，否则应用拒绝启动。

`DEAD` 表示需要人工介入：`last_error` 会清理控制字符并安全截断。若关联任务仍为 `PENDING`，同一事务会把它置为 `FAILED_RETRYABLE / DELIVERY_FAILED`，且 `next_retry_at=null`，因此自动 retry scheduler 不会立即重试；已经 `RUNNING`、`SUCCESS` 或进入其他状态的任务不会被覆盖。修复 broker、路由或凭据后，在原任务详情中点击“重新分析”（或调用现有 retry API）会创建一条新的 outbox 事件。不要直接把旧 `DEAD` 事件改回 `PENDING`。建议对 `analysis.outbox.backlog{status="dead"} > 0` 和 `analysis.outbox.events{outcome="dead"}` 告警。

终态事件不是永久审计库：retention scheduler 默认每小时执行一批，删除 `terminal_at` 超过 `ANALYSIS_OUTBOX_RETENTION=30d` 的 `PUBLISHED/DEAD` 行；每轮最多删除 `ANALYSIS_RETENTION_BATCH_SIZE=200` 行。提交幂等记录也默认保留 30 天，但只有未绑定任务，或关联任务已为 `SUCCESS/FAILED_FINAL/CANCELLED/FAILED` 时才会删除；关联 `PENDING/RUNNING/FAILED_RETRYABLE` 的记录会保留，避免旧请求在任务仍活跃时失去幂等保护。幂等记录清理后，同一个 key 可以再次使用。

用户数据另受 `ANALYSIS_USER_DATA_RETENTION=30d` 限制：按 task `created_at` 超过截止时间的记录，无论状态都会分批删除任务、报告、Redis cache、幂等/outbox，以及不再被任何任务引用的简历/JD。这避免匿名会话过期后，卡住的活跃状态变成无法访问却永久保留的数据。用户也可在详情页确认后立即调用 `DELETE /api/analysis/{taskId}`；跨会话与不存在都返回 404。删除运行中任务不能撤回已经发送给模型提供方的 HTTP 请求，但 attempt fence 会拒绝迟到结果落库。停机时 scheduler 最多等待 `SCHEDULER_SHUTDOWN_TIMEOUT=10s` 完成当前事务批次。

### 6.4 Redis 或报告缓存异常

Redis 是缓存，不是事实来源。Redis 异常不应导致报告数据丢失。

检查：

```powershell
docker compose ps redis
docker compose exec redis redis-cli -a $env:REDIS_PASSWORD ping
```

如 Redis 不可用，优先确认 MySQL 中报告是否存在。

应用默认通过 `REDIS_CONNECT_TIMEOUT=500ms` 和 `REDIS_COMMAND_TIMEOUT=500ms` 限制缓存等待；一次报告回源最多可能先经历 cache get 和回填 cache put 两次失败。可根据同机房 Redis 的延迟分位调整，但不要移除上限，否则 cache-aside 在依赖故障时可能占满 HTTP worker。

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

日志只应包含任务 ID、模型、step、耗时和错误类别，不应出现简历/JD、prompt、工具参数或模型原文。`SensitiveTelemetryPolicyTest` 会扫描自有 Java/Python telemetry 调用，Java worker 与 Python Agent 测试还会把正文 canary 送过成功链路并检查捕获日志；这是 source-level 与测试路径门禁，不是第三方日志 DLP 或生产 trace 扫描。若模型经常触发上限，先用 `agent-service/evals` 复现并改进 system prompt/模型选择；不要简单取消边界。

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

### 6.9 结构化报告无法显示或被拒绝

Agent 新报告应满足：`reportSchemaVersion=match-report-v2`，且 `structuredReport.schemaVersion=match-report-v2`、`provenance.schemaVersion=analysis-run-v1`、`provenance.runMetadata.schemaVersion=agent-run-v1`。旧数据应返回 `markdown-v1`，两个结构化字段为 `null`；滚动升级期间，旧 `analysis-run-v1` 没有 `runMetadata` 仍可读取。

检查：

```powershell
curl.exe -sS `
  -H "X-API-Token: dev-token" `
  http://localhost:8080/api/analysis/1/report
docker compose logs app --tail 100
```

- Agent 返回 2xx 但任务进入 `REPORT_PARSE_FAILED`：检查 score breakdown、status 单调性、verification evidence 子集、最终 evidence 闭包，以及 `runMetadata` 的请求/运行时版本、输入指纹、Chat calls=steps、tool duration=tool trace sum；Java 会独立重算，不接受只在 JSON 形状上正确的报告。
- API 返回结构化字段但前端显示“报告暂不可用”：使用浏览器 Network 查看响应，并用前端 Zod 测试复现。Zod 同样会重算 score 和 evidence graph。
- 不要通过删除校验或手工改库绕过问题。先修复 Agent contract；已生成的非法结构化报告不应标记为成功。
- provenance 只用于运行版本、聚合 usage 和同任务输入一致性回查。输入指纹不是加密或匿名化，不得复制到日志、metric label 或 trace attribute；若 provenance 中出现简历/JD、工具参数或隐藏推理，应按数据泄漏处理并停止发布。

## 7. 数据与安全

- 不要把真实简历、JD、AI prompt 或 AI 原始输出贴入 issue、commit message、日志样例或文档。
- 当前服务会把 JD 和检索片段发送给配置的 chat provider；启用 `hybrid` 时，还会把简历 chunks 与检索 query 发送给配置的 embedding provider。两者可能不是同一供应商，部署前必须分别完成数据处理与保留条款审查。
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
- `V5__add_structured_match_report.sql` 为旧报告填充 `report_schema_version=markdown-v1`，并新增 nullable 的结构化报告/provenance 长文本列；新应用仍能读取旧行。应用和 schema 应在同一维护窗口发布。
- `V7__add_outbox_retention_metadata.sql` 会用旧 `PUBLISHED.published_at` 或行 `created_at` 回填 `terminal_at`。若历史终态行早于配置的保留期，它们会在新应用启动后的下一轮 retention 中成为清理候选；需要长期审计时，应在开放流量前调整 retention 或先导出这些运维元数据。
- `V9__drop_duplicate_resume_summary.sql` 删除与 `raw_text` 重复的 `resume.structured_summary`。发布前确认没有外部查询或私有代码把该列改作其他用途并保留备份；`DROP COLUMN` 可能持有 metadata lock，应在维护窗口执行并用目标 MySQL 版本/数据量预演。H2 MySQL-mode 已覆盖 V1→V9，真实 MySQL migration 仍由 Testcontainers/发布预演验证。

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

追踪定位：用 observability overlay 创建一次分析，成功后在报告“运行溯源”复制 Trace ID；打开 Grafana `Explore`，选择 `Tempo` 数据源并按 Trace ID 查询。预期同一 trace 至少能看到 Java API、RabbitMQ producer/consumer、Java 到 Agent 的 HTTP、FastAPI server，以及实际发生的 chat/embedding HTTP client span。异步 outbox 会把安全的 W3C `traceparent` 随事件持久化，因此延迟发布仍能关联最初请求；旧数据或关闭 tracing 时 `traceId=null` 是合法滚动兼容状态。

基础 Compose 默认 `TRACING_ENABLED=false`、`AGENT_TRACING_ENABLED=false`，避免未启动 collector 时无意义导出。手动本机调试时可同时开启两项并把 endpoint 指向可达的 OTLP/HTTP `/v1/traces`。自动 instrumentation 不记录 body 或鉴权 header；不要把 token、简历/JD 或其他个人数据放进 URL query，Tempo volume 也应按候选人数据环境的访问与删除要求保护。

日志中可按 `requestId`、`correlationId`、`taskId` 和 `event=analysis_task_*` 搜索任务生命周期。任务日志只记录 ID、状态和失败分类，不记录简历原文、JD 原文、prompt 或 AI 原始响应。

指标检查：

```powershell
curl.exe -i http://localhost:8080/actuator/metrics
curl.exe -i http://localhost:8080/actuator/prometheus
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
curl.exe -i http://localhost:8080/actuator/metrics/analysis.outbox.oldest.age
curl.exe -i http://localhost:8080/actuator/metrics/analysis.retention.deleted
curl.exe -i http://localhost:8000/metrics
```

`analysis.outbox.backlog` 的 `status` 包括 `pending`、`failed`、`processing` 和终态 `dead`；`analysis.outbox.oldest.age` 是最老非终态事件的秒龄，Prometheus 名称为 `analysis_outbox_oldest_age_seconds`，未来时钟偏移会保守钳制为 0。`analysis.outbox.events` 的 `outcome=dead` 记录本进程观察到的终态转换次数。`analysis.retention.deleted` 以 `resource=outbox|idempotency|analysis_user_data` 区分本进程清理的行数，`report.cache.evictions{outcome}` 记录主动删除的缓存驱逐结果。Gauge 是数据库当前事实，counter 适合观察转换或清理速率。

Agent 的 `agent_service_analyses_total`、`agent_service_provider_calls_total`、`agent_service_tool_calls_total`、`agent_service_retrieval_calls_total`、`agent_service_protocol_errors_total` 和对应 duration histogram 用固定 outcome/reason/tool/retriever 标签；`agent_service_analyses_in_progress`、`agent_service_analysis_capacity_limit` 与 `agent_service_analysis_capacity_rejections_total` 分别显示当前在途、单进程上限和 provider 调用前的容量拒绝。`agent_service_model_tokens_total{type,provider_reported}` 只累计 provider 返回的数字，`provider_reported="false"` 时可能不完整。价格启用状态、累计估算、估算次数与跳过原因分别由 `agent_service_cost_estimation_enabled`、`agent_service_model_estimated_cost_usd_total`、`agent_service_cost_estimates_total`、`agent_service_cost_estimation_skipped_total{reason}` 暴露；价格/版本本身不作为指标标签。不要给这些指标增加 task ID、correlation ID、query、文件名、异常消息、模型输出或动态版本标签。

observability overlay 中的告警规则位于 `observability/prometheus/alerts.yml`，覆盖 Java/Agent target down、最老 outbox 积压超过 120 秒、dead backlog、带最小样本门槛的任务/provider 失败率、重复 Agent 协议错误、重复容量耗尽，以及价格已启用但多次缺失完整 usage。启动后可在 Prometheus `/alerts` 检查加载/触发状态，在 15-panel Grafana dashboard 查看趋势，在 Explore/Tempo 查看跨 Java、RabbitMQ、Agent 与 provider 的 W3C trace。Tempo 使用本地 volume 且默认只保留 24 小时。该栈仍是本地观测能力，不代表已经有 provider 账单对账、生产通知、租户级 trace 权限或长期保留闭环。

RabbitMQ 队列深度仍通过 RabbitMQ 管理页或命令检查：

```powershell
docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
```
