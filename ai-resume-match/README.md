# 基于 RAG 的智能简历与岗位匹配系统

这是一个可直接使用的全栈 AI 应用。用户可在浏览器中上传 PDF/DOCX 简历、填写岗位信息、跟踪异步分析状态，并查看历史记录和 Markdown 匹配报告；后端通过文档解析、本地向量检索、RAG 上下文组装和 OpenAI-compatible API 生成结果。

## 技术栈

Java 21、Spring Boot 3.3、Spring Data JPA、Flyway、MySQL、Redis、RabbitMQ、React 19、TypeScript、Vite、TanStack Query、React Hook Form、Zod、Nginx、Playwright、Docker Compose、JUnit 5、Testcontainers。

## 架构概览

- Spring Boot 单体应用承载 API、用例编排、worker、RAG 和基础设施适配。
- MySQL 是简历、JD、分析任务、报告和 outbox 的事实来源。
- RabbitMQ 只传递 `taskId`，worker 从 MySQL 重新读取业务数据。
- Redis 只做报告查询 cache-aside，缓存失败不影响业务结果。
- Flyway 管理 schema，运行时使用 `ddl-auto=validate`。
- React 前端提供总览、历史、原子提交、状态轮询、失败重试和安全 Markdown 报告。
- Nginx 托管前端并代理同源 `/api`；`API_TOKEN` 只在代理边界注入，不进入浏览器 bundle 或存储。
- Docker Compose 可启动 frontend、app、MySQL、Redis、RabbitMQ，并使用健康检查和持久化 volume。

## 完整 Docker Compose 启动

```powershell
Copy-Item .env.example .env
notepad .env
docker compose up -d --build
curl.exe -i http://localhost:3000/frontend-health
curl.exe -i http://localhost:8080/actuator/health/readiness
```

`.env` 中至少需要设置 `AI_API_KEY` 和 `API_TOKEN`。示例值仅用于本地开发，不要提交真实 `.env`、真实 token 或真实 API key。

启动后访问：

- 产品前端：`http://localhost:3000`
- 后端 API/Actuator：`http://localhost:8080`

浏览器只访问同源 Nginx。Nginx 在转发 `/api` 时注入 `X-API-Token`，前端代码不会读取或持久化 `API_TOKEN`。

停止服务：

```powershell
docker compose down
```

## 本机 Maven 开发启动

如需本机运行应用、只用 Docker 启动依赖：

```powershell
Copy-Item .env.example .env
notepad .env
docker compose up -d mysql redis rabbitmq
$env:SPRING_PROFILES_ACTIVE="dev"
$env:API_TOKEN="dev-token"
$env:AI_API_KEY="replace-with-local-dev-key"
mvn spring-boot:run
```

`dev` profile 默认连接 `localhost` 上的 MySQL、Redis、RabbitMQ；`docker` profile 使用 Compose 服务名；`prod` profile 不包含本地默认凭据。

前端本机开发：

```powershell
npm --prefix frontend ci
$env:API_PROXY_TARGET="http://localhost:8080"
$env:API_TOKEN="dev-token"
npm --prefix frontend run dev
```

`API_TOKEN` 由 Vite 开发代理读取并写入上游请求，不会通过 `VITE_*` 暴露给浏览器。

## 环境变量

| 变量 | 说明 | 示例 |
| --- | --- | --- |
| `FRONTEND_PORT` | Nginx 前端暴露端口 | `3000` |
| `APP_PORT` | app 暴露端口 | `8080` |
| `MYSQL_DATABASE` | MySQL database | `ai_resume_match` |
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | `dev-root-password` |
| `MYSQL_USER` | 应用连接 MySQL 用户 | `ai_match` |
| `MYSQL_PASSWORD` | 应用连接 MySQL 密码 | `dev-mysql-password` |
| `REDIS_PASSWORD` | Redis 密码 | `dev-redis-password` |
| `RABBITMQ_DEFAULT_USER` | RabbitMQ 用户 | `ai_match` |
| `RABBITMQ_DEFAULT_PASS` | RabbitMQ 密码 | `dev-rabbit-password` |
| `API_TOKEN` | `X-API-Token` 校验值 | `dev-token` |
| `AI_API_KEY` | AI provider API key | `replace-with-your-dev-key` |
| `AI_ENDPOINT` | OpenAI-compatible endpoint | `https://api.openai.com/v1/chat/completions` |
| `AI_MODEL` | AI model name | `gpt-4o-mini` |

更多 outbox、retry 和 cache 参数见 `.env.example`。

## 核心接口

所有业务接口需要携带请求头：`X-API-Token: <API_TOKEN>`。

- `POST /api/resumes`：上传 PDF/DOCX 简历。
- `POST /api/jobs`：提交岗位名称和 JD；旧客户端只传 `content` 仍兼容。
- `POST /api/analysis`：根据已有简历/JD 创建异步任务，保留旧客户端兼容。
- `POST /api/analysis-submissions`：一次 multipart 请求原子创建简历、JD、任务和 outbox 事件，前端默认使用。
- `GET /api/analysis`：分页查询历史，可按状态筛选。
- `GET /api/analysis/summary`：查询总数、完成数、处理中、可重试失败和平均分。
- `GET /api/analysis/{taskId}`：查询任务状态。
- `GET /api/analysis/{taskId}/report`：查询匹配报告。
- `POST /api/analysis/{taskId}/retry`：手动重试可重试失败任务。

健康检查：

```powershell
curl.exe -i http://localhost:8080/actuator/health/readiness
```

readiness 只暴露 Spring 应用自身的接流量状态；MySQL、Redis、RabbitMQ 的容器健康由 Docker Compose healthcheck 管理，Redis 作为 cache-aside 依赖不可用时不应改变业务事实来源。

请求链路会返回 `X-Request-Id` 和 `X-Correlation-Id`。客户端可以传入这两个 header；缺失或非法时服务会生成安全 UUID。错误响应包含 `requestId`，便于在日志中定位同一次请求。

常用指标入口：

```powershell
curl.exe -i http://localhost:8080/actuator/metrics
curl.exe -i http://localhost:8080/actuator/metrics/analysis.tasks.created
curl.exe -i http://localhost:8080/actuator/metrics/analysis.outbox.backlog
```

## 测试与验证

```powershell
mvn test
mvn verify
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend run test
npm --prefix frontend run build
npm --prefix frontend run test:e2e
npm --prefix frontend run test:e2e:full-stack
docker compose --env-file .env.example config --quiet
docker build -t ai-resume-match:local .
```

`mvn test` 跑后端 fast tests；`mvn verify` 使用 Testcontainers 验证迁移、outbox、RabbitMQ、Redis 和后端 E2E。`test:e2e` 使用浏览器内确定性 mock，不启动 Docker；`test:e2e:full-stack` 会构建独立 Compose 项目，通过真实 Spring/MySQL/Redis/RabbitMQ 链路完成一次浏览器分析，并在结束后删除测试容器和 volume。

## 文档

- 当前工程化设计：`docs/superpowers/specs/2026-07-04-engineering-hardening-design.md`
- 前端产品设计：`docs/superpowers/specs/2026-07-10-frontend-product-experience-design.md`
- 当前开发入口：`docs/development.md`
- 运维手册：`docs/operations/runbook.md`
- 历史实现计划：`docs/superpowers/plans/2026-05-12-rag-resume-job-match.md`
