# AI Resume Match Operations Runbook

本文档记录 `ai-resume-match` 当前可执行的本地运行、检查和排障流程。它覆盖 Phase 1 后的状态；Actuator、outbox、重试接口和完整 Docker 化应用服务将在后续阶段补充。

## 1. 本地启动

进入项目目录：

```powershell
cd "C:\Users\chen\Documents\New project 2\ai-resume-match"
```

启动依赖服务：

```powershell
docker compose up -d
```

设置必要环境变量：

```powershell
$env:API_TOKEN="dev-token"
$env:AI_API_KEY="replace-with-local-dev-key"
```

启动应用：

```powershell
mvn spring-boot:run
```

默认依赖：

- MySQL：`localhost:3306`，database `ai_resume_match`，用户名 `root`，密码 `root`。
- Redis：`localhost:6379`。
- RabbitMQ：`localhost:5672`，用户名 `guest`，密码 `guest`。
- RabbitMQ 管理页：`http://localhost:15672`。

## 2. 停止服务

停止应用：在运行 `mvn spring-boot:run` 的终端按 `Ctrl+C`。

停止依赖：

```powershell
docker compose down
```

如需清空本地容器数据，先确认不会丢失需要保留的测试数据，再执行带 volume 清理的 Docker 命令。

## 3. 快速检查

查看容器状态：

```powershell
docker compose ps
```

运行 fast tests：

```powershell
mvn test
```

检查 API token 拦截：

```powershell
curl.exe -i http://localhost:8080/api/analysis/1
```

预期：没有 `X-API-Token` 时返回 `401`，body 为结构化错误。

检查任务状态接口：

```powershell
curl.exe -i `
  -H "X-API-Token: dev-token" `
  http://localhost:8080/api/analysis/1
```

预期：任务存在时返回任务状态；任务不存在时返回 `404`。

## 4. 基础业务 smoke flow

上传简历：

```powershell
curl.exe -i `
  -H "X-API-Token: dev-token" `
  -F "file=@C:\Users\chen\Documents\sample-resume.pdf" `
  http://localhost:8080/api/resumes
```

创建 JD：

```powershell
curl.exe -i `
  -H "X-API-Token: dev-token" `
  -H "Content-Type: application/json" `
  -d "{\"content\":\"Java Spring Boot Redis RabbitMQ\"}" `
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

注意：示例文件路径和 ID 需要替换为本地实际值，不要使用真实敏感简历做共享演示。

## 5. 常见问题

### 5.1 返回 401

症状：

- API 返回 `{"code":"UNAUTHORIZED","message":"Unauthorized"}`。

检查：

- 请求是否带 `X-API-Token`。
- 请求头值是否等于当前终端的 `$env:API_TOKEN`。
- 应用是否在设置环境变量之后启动。

### 5.2 返回 400 INVALID_REQUEST

症状：

- JSON 参数缺失、字段为空或 ID 非正数。

检查：

- `POST /api/jobs` 的 `content` 不得为空。
- `POST /api/analysis` 的 `resumeId` 和 `jobDescriptionId` 必须是正数。
- `Content-Type` 是否为 `application/json`。

### 5.3 上传简历失败

症状：

- 返回 `BAD_REQUEST`。
- message 可能是文件为空、文件过大、扩展名不支持或解析文本为空。

检查：

- 文件扩展名是否为 `.pdf` 或 `.docx`。
- 文件大小是否不超过 `resume.upload.max-file-size`，默认 `5MB`。
- PDF/DOCX 是否包含可提取文本。
- 当前不支持图片扫描件 OCR。

### 5.4 任务一直 PENDING

可能原因：

- RabbitMQ 未启动。
- 应用创建任务后发布消息失败。
- worker 未正常监听队列。

检查：

```powershell
docker compose ps rabbitmq
```

打开 RabbitMQ 管理页检查队列是否存在消息堆积。

当前限制：

- Phase 3 前还没有 outbox，极端情况下可能出现任务已创建但消息未成功投递。此类任务需要人工重新创建分析任务。

### 5.5 任务失败

可能原因：

- AI API key 无效。
- AI endpoint 不可达或超时。
- AI 返回内容无法解析匹配分数。
- 简历或 JD 源数据缺失。

检查：

- `$env:AI_API_KEY` 是否设置。
- `src/main/resources/application.yml` 中 `ai.endpoint` 和 `ai.model` 是否符合当前供应商。
- 应用日志中按 `taskId` 搜索失败记录。

当前限制：

- Phase 2/3 前失败码和重试次数还不完整。
- Phase 3 前没有正式 retry endpoint。

### 5.6 查询报告慢或缓存异常

原则：

- Redis 是缓存，不是事实来源。
- Redis 异常不应导致报告数据丢失。

检查：

```powershell
docker compose ps redis
```

如 Redis 不可用，优先确认 MySQL 中报告是否存在。

## 6. 数据与安全

- 不要把真实简历、JD、AI prompt 或 AI 原始输出贴入 issue、commit message、日志样例或文档。
- 当前服务会把简历/JD 相关内容发送给配置的 AI provider。
- `.env`、真实 API key、真实 token 不得提交。
- 本地默认数据库密码只用于开发环境，生产环境必须改为环境变量注入。

## 7. 后续运维补强点

后续阶段落地后，本手册需要同步补充：

- Actuator readiness/liveness/health endpoint。
- Dockerfile 和 app service 的 compose 启动方式。
- Flyway migration 检查和回滚策略。
- outbox backlog 检查和重放流程。
- failed retryable task 的重试流程。
- RabbitMQ DLQ 检查流程。
- metrics、结构化日志、request ID 和 correlation ID 查询示例。
