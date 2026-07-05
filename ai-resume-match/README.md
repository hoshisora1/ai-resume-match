# 基于 RAG 的智能简历与岗位匹配系统

这是一个后端主导的 AI 应用项目。系统支持上传 PDF/DOCX 简历、提交岗位 JD、创建异步分析任务，并通过文档解析、本地向量检索、RAG 上下文组装和 OpenAI-compatible API 生成岗位匹配报告。

## 技术栈

Java 21、Spring Boot 3.3、Spring MVC、Spring Data JPA、Flyway、MySQL、Redis、RabbitMQ、PDFBox、Apache POI、local hashing embedding、cosine similarity、OpenAI-compatible API、Docker Compose、JUnit 5、Testcontainers。

## 架构概览

- Spring Boot 单体应用承载 API、用例编排、worker、RAG 和基础设施适配。
- MySQL 是简历、JD、分析任务、报告和 outbox 的事实来源。
- RabbitMQ 只传递 `taskId`，worker 从 MySQL 重新读取业务数据。
- Redis 只做报告查询 cache-aside，缓存失败不影响业务结果。
- Flyway 管理 schema，运行时使用 `ddl-auto=validate`。
- Docker Compose 可启动 app、MySQL、Redis、RabbitMQ，并使用健康检查和持久化 volume。

## 完整 Docker Compose 启动

```powershell
Copy-Item .env.example .env
notepad .env
docker compose up -d --build
curl.exe -i http://localhost:8080/actuator/health/readiness
```

`.env` 中至少需要设置 `AI_API_KEY` 和 `API_TOKEN`。示例值仅用于本地开发，不要提交真实 `.env`、真实 token 或真实 API key。

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

## 环境变量

| 变量 | 说明 | 示例 |
| --- | --- | --- |
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
- `POST /api/jobs`：提交岗位 JD。
- `POST /api/analysis`：创建异步分析任务。
- `GET /api/analysis/{taskId}`：查询任务状态。
- `GET /api/analysis/{taskId}/report`：查询匹配报告。
- `POST /api/analysis/{taskId}/retry`：手动重试可重试失败任务。

健康检查：

```powershell
curl.exe -i http://localhost:8080/actuator/health/readiness
```

readiness 只暴露 Spring 应用自身的接流量状态；MySQL、Redis、RabbitMQ 的容器健康由 Docker Compose healthcheck 管理，Redis 作为 cache-aside 依赖不可用时不应改变业务事实来源。

## 测试与验证

```powershell
mvn test
mvn verify
docker compose --env-file .env.example config
docker build -t ai-resume-match:phase4 .
```

`mvn test` 跑 fast tests；`mvn verify` 通过 Testcontainers 验证 MySQL Flyway migration 和 MySQL+RabbitMQ outbox 生命周期。

## 文档

- 当前工程化设计：`docs/superpowers/specs/2026-07-04-engineering-hardening-design.md`
- 当前开发入口：`docs/development.md`
- 运维手册：`docs/operations/runbook.md`
- 历史实现计划：`docs/superpowers/plans/2026-05-12-rag-resume-job-match.md`
