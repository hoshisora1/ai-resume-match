# AI Resume Match Development Guide

本文档是 `ai-resume-match` 后续工程化重构的日常开发入口。详细设计以
`docs/superpowers/specs/2026-07-04-engineering-hardening-design.md` 为准；每个阶段的具体改动以
`docs/superpowers/plans/` 下对应计划为准。

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
   ```

5. 按计划逐项执行。

   - 先写或调整测试，确认测试因预期原因失败。
   - 实现最小必要改动。
   - 跑聚焦测试并确认通过。
   - 对同一任务做规格符合性检查和代码质量检查。
   - 提交一个范围清晰的 commit。

6. 阶段完成后运行全量测试。

   ```powershell
   mvn test
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
```

PowerShell 中 `-Dtest=A,B` 推荐整体加引号，避免参数解析问题。

当前 fast test suite 使用 JUnit 5、Mockito、AssertJ、MockMvc、H2。集成测试使用 Failsafe 和 Testcontainers，当前覆盖 MySQL Flyway 校验，以及 MySQL+RabbitMQ outbox 生命周期、publisher confirm/return 行为：

```powershell
mvn verify
```

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

报告解析：

- AI 输出解析放在独立组件中，例如 `ReportParser`。
- 分数边界、缺失字段、非法输出都应有聚焦测试。
- 后续结构化 JSON 输出落地后，应优先解析 JSON，再保留文本 fallback。

配置：

- 不提交真实 token、真实 API key、真实简历或 JD。
- 本地默认值只用于 `dev` profile；`docker` 和 `prod` profile 通过环境变量注入运行配置。
- `prod` profile 使用 `ddl-auto=validate` 和 Flyway migration，不包含 `root`、`guest`、`localhost` 或开发 token 默认值。
- `.env` 不得提交，`.env.example` 只保存示例值。

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
4. 每个实现任务后做两轮独立检查：规格符合性、代码质量。
5. 主线程最终运行测试并提交。

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
- Phase 6：下一步。端到端验证、mock AI HTTP server、PDF/DOCX fixtures、`mvn verify`。

`docs/superpowers/plans/2026-05-12-rag-resume-job-match.md` 保留为历史实现上下文，不作为当前工程化重构的执行计划。
