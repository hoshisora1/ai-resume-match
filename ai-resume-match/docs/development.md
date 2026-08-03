# AI Resume Match Development Guide

本文档是 `ai-resume-match` 的日常开发入口。后端工程化设计见
`docs/superpowers/specs/2026-07-04-engineering-hardening-design.md`，前端产品设计见
`docs/superpowers/specs/2026-07-10-frontend-product-experience-design.md`，当前 Agent 边界见
`docs/agent-engineering-evidence.md`；阶段改动以 `docs/superpowers/plans/` 下对应计划为准。

## 1. 基本原则

- 先读设计文档和当前阶段计划，再改代码。
- 大改动使用独立 worktree 和 `codex/` 前缀分支，主分支保持可验证。
- 每个行为变更先补测试，再实现，再运行聚焦测试和全量 fast tests。
- 任务完成标准是代码、测试、文档和提交记录同时闭环。
- 简历、JD、AI prompt、AI 原始响应都可能包含敏感信息，日志和文档示例不得写入真实内容。

## 2. 推荐工作流

1. 查看工作区状态。

   ```powershell
   git status --short --branch
   ```

2. 阅读当前阶段资料。

   ```powershell
   Get-Content -Raw docs/superpowers/specs/2026-07-04-engineering-hardening-design.md
   Get-ChildItem docs/superpowers/plans
   Get-Content -Raw docs/superpowers/plans/2026-07-04-phase-1-safety-net-contracts.md
   ```

3. 对阶段级重构创建隔离 worktree。

   ```powershell
   git worktree add .worktrees/<phase-name> -b codex/<phase-name>
   ```

4. 在改动前运行一次基线测试。

   ```powershell
   mvn test
   Set-Location agent-service
   .\.venv\Scripts\python.exe -m pytest
   Set-Location ..
   ```

5. 按计划逐项执行。

   - 先写或调整测试，确认测试因预期原因失败。
   - 实现最小必要改动。
   - 跑聚焦测试并确认通过。
   - 对同一任务做一次合并的规格与代码质量检查。
   - 集中修复重要发现并直接验证；只有关键行为发生较大变化时才补一次短复查。
   - 提交一个范围清晰的 commit。

6. 阶段完成后运行全量测试。

   ```powershell
   mvn test
   Set-Location agent-service
   .\.venv\Scripts\python.exe -m pytest
   Set-Location ..
   ```

7. 回到主分支合并。

   ```powershell
   git checkout master
   git merge --ff-only codex/<phase-name>
   mvn test
   git worktree remove .worktrees/<phase-name>
   git branch -d codex/<phase-name>
   ```

## 3. 测试命令

常用命令：

```powershell
mvn test
mvn "-Dtest=ResumeMatchControllerTest" test
mvn "-Dtest=RunAnalysisUseCaseTest,AnalysisWorkerTest" test
Set-Location agent-service
.\.venv\Scripts\python.exe -m pytest
Set-Location ..
```

PowerShell 中 `-Dtest=A,B` 推荐整体加引号，避免参数解析问题。

当前 fast test suite 使用 JUnit 5、Mockito、AssertJ、MockMvc、H2。集成测试使用 Failsafe 和 Testcontainers，当前覆盖 MySQL Flyway 校验、MySQL+RabbitMQ outbox 生命周期、publisher confirm/return 行为、Redis cache-aside，以及 mock AI HTTP server 驱动的 PDF/DOCX 端到端分析流：

```powershell
mvn verify
```

前端开发与验证：

```powershell
npm --prefix frontend ci
npm --prefix frontend run dev
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend run test
npm --prefix frontend run build
```

浏览器测试分两层：

```powershell
# 同源 API 使用确定性 mock，覆盖主流程、错误分支和桌面/移动布局
npm --prefix frontend run test:e2e

# 构建独立 Compose 项目，覆盖真实 Spring/outbox/RabbitMQ/Redis/Agent/AI mock 链路
npm --prefix frontend run test:e2e:full-stack
```

`test:e2e:full-stack` 需要 Docker Desktop，使用专用端口和 volume，并在 `finally` 中清理。不要把 full-stack spec 并入普通浏览器套件运行。

## 4. 代码约定

API 层：

- Controller 返回显式 DTO，不返回临时 `Map`。
- 成功响应使用 `ResumeUploadResponse`、`JobDescriptionResponse`、`AnalysisTaskResponse` 等明确类型。
- 错误响应使用 `ApiErrorResponse`，至少包含 `code` 和 `message`。
- `401`、参数校验 `400`、业务 `400`、资源不存在 `404` 都应有结构化响应。

上传与文档解析：

- 上传文件先经过 `ResumeFileValidator`，再进入 `DocumentTextExtractor`。
- 当前仅支持 PDF/DOCX。
- 空文件、超限文件、不支持扩展名、空解析文本都应被拒绝。

分析任务：

- API 只创建任务和查询状态，不在请求线程内执行 AI 分析。
- RabbitMQ 消息体只传 `taskId`，worker 从 MySQL 重新读取任务、简历和 JD。
- MySQL 是任务状态和报告结果的事实来源，Redis 只做 report cache-aside。
- `AnalysisWorker` 只负责监听 RabbitMQ 并委托 `RunAnalysisUseCase`。
- 分析任务编排、失败分类、状态转换和手动 retry 入口优先落在 application/domain 组件中。
- `RunAnalysisUseCase` 只依赖 `AnalysisEngine`；Agent 与 legacy RAG 的选择由配置和条件 bean 完成。

Agent runtime：

- 初始模型上下文只放 task ID；简历、JD、标题和标签必须通过工具作为 untrusted data 返回。
- 新工具必须加入白名单、Pydantic schema、调用前置条件、预算和确定性测试，不能只写进 prompt。
- 普通模型文本不能作为最终报告；最终结果只能由 `submit_match_report` 的有效结构化参数产生。
- evidence ID 必须来自当前分析的检索结果；未知引用和越界报告字段必须拒绝。
- 模型/工具循环必须有 step、tool call、protocol error 和 HTTP timeout 上限。
- 原有 `ReportParser` 只服务 `legacy` 引擎，不应重新进入 Agent 主链路。

配置：

- 不提交真实 token、真实 API key、真实简历或 JD。
- 本地默认值只用于 `dev` profile；`docker` 和 `prod` profile 通过环境变量注入运行配置。
- `prod` profile 使用 `ddl-auto=validate` 和 Flyway migration，不包含 `root`、`guest`、`localhost` 或开发 token 默认值。
- `.env` 不得提交，`.env.example` 只保存示例值。

前端：

- API DTO 必须先经过 Zod 运行时校验，再进入页面状态。
- 数据读取使用 TanStack Query；敏感 multipart 提交使用可取消的直接请求，卸载时终止。
- 状态与错误通过可访问名称、live region 和结构化 request ID 呈现。
- 报告 Markdown 禁用远程图片，不允许危险 scheme；不要把简历、JD、token 写入 Web Storage。
- 生产浏览器只访问 Nginx 同源代理；`API_TOKEN` 不得进入 `VITE_*`、bundle、DOM 或前端日志。

## 5. 子代理使用

适合使用子代理的场景：

- 一个阶段计划拆成多个互不重叠的任务。
- 需要独立阅读同一份计划做规格审查。
- 需要独立做代码质量审查。
- 需要并行梳理文档、测试缺口或迁移风险。

推荐模式：

1. 主线程负责读设计、拆任务、维护计划和最终集成。
2. worker 子代理只处理明确文件范围内的实现任务。
3. explorer 子代理只回答具体问题，不直接改文件。
4. 每个主要任务最多安排一次合并审查，优先报告会影响正确性、安全性、隐私或交付的问题。
5. 发现集中修复一次并由主线程直接验证；避免在同一代码上重复发起无新增信息的复审。
6. 主线程最终运行测试并提交。

给子代理的提示应包含：

- 当前仓库路径。
- 任务对应的计划章节。
- 可修改文件范围。
- 不要回退他人改动。
- 最终回复列出变更文件和验证命令。

## 6. 提交约定

提交应小而完整，推荐前缀：

- `docs:` 文档。
- `test:` 测试契约或测试夹具。
- `feat:` 用户可见能力。
- `fix:` 缺陷修复。
- `refactor:` 不改变外部行为的结构调整。
- `chore:` 构建、忽略文件、工具配置。

提交前检查：

```powershell
git status --short
mvn test
```

阶段级合并后，在主分支再次运行 `mvn test`。

## 7. 阶段路线

- Phase 1：已完成。API DTO、结构化错误、上传校验、任务状态接口、报告解析组件、回归测试。
- Phase 2：已完成。引入 use case、任务状态转换、失败码、attempts、手动 retry 和薄 worker。
- Phase 3：已完成。Flyway、outbox、publisher confirm/return、自动重试调度、RabbitMQ 集成测试。
- Phase 4：已完成。Dockerfile、app compose service、health checks、profiles、`.env.example`、README 启动流。
- Phase 5：已完成。request/correlation ID、结构化任务日志、Micrometer 指标、运行手册补强。
- Phase 6：已完成。端到端验证、mock AI HTTP server、PDF/DOCX fixtures、Redis Testcontainers、`mvn verify`。
- 前端产品化：已完成。原子提交、历史/汇总 API、React 工作台、状态轮询与重试、安全报告、Nginx/Compose 交付、Playwright 和真实全栈验收。
- Agent 化：已完成代码实现。Python/FastAPI sidecar、三工具有界循环、结构化终止、证据校验、Java 双引擎适配、内部鉴权、指标、合成 eval 和 Compose/E2E mock 接线。

当前提交前 fast gate 是：`mvn test`、Agent `pytest`、前端 lint/typecheck/unit/build 和 Compose config。Docker-backed `mvn verify` 与 `test:e2e:full-stack` 仍是完整验收 gate；不能在 Docker daemon 未运行时把它们记为本次已通过。

`docs/superpowers/plans/2026-05-12-rag-resume-job-match.md` 保留为历史实现上下文，不作为当前工程化重构的执行计划。
