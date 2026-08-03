# Agent 工程证据与面试说明

本文档只记录可以由仓库代码、测试或运行结果复核的事实，用于项目复盘、简历取材和技术面试。它不是虚构实习证明。

## 1. 项目定位

项目从“固定 RAG prompt -> 一次模型调用 -> 正则解析分数”演进为“可靠任务系统 + 受限 Tool Calling Agent”：

```text
React/Nginx
  -> Spring Boot API / MySQL / outbox / RabbitMQ worker
  -> AnalysisEngine
       -> Python FastAPI Agent
            -> get_job_requirements
            -> search_resume_evidence
            -> submit_match_report
            -> OpenAI-compatible model
       -> legacy Java RAG fallback
  -> report persistence / Redis cache / UI polling
```

Java 是业务主系统，拥有任务状态、事务、重试和最终报告；Python 只负责无状态 Agent 编排。模型不能直接访问数据库、文件系统或网络工具。

## 2. 为什么这不是简单工作流套壳

Agent 具备模型驱动的动作选择：模型可以决定检索 query、top-K、是否继续检索以及何时提交。但自主性被限制在明确的 capability 边界内：

- 只注册三个 allowlisted function tools，未知工具直接拒绝。
- 初始模型上下文除固定指令外只含 task ID；岗位标题、标签、简历和 JD 均不直接注入。
- JD 和检索片段由工具返回，并标记为 untrusted data，降低正文提示注入影响。
- 工具参数由 Pydantic 校验，拒绝未知字段、非法 JSON、越界长度和越界 top-K。
- 调用顺序由状态机校验：先读取 JD，再检索证据，最后提交。
- `coreClaims` 与 `matchedSkills` 的每条正向 claim 必须携带自己的 evidence ID，且 ID 必须来自本轮非空检索结果；未知或跨运行 ID 会被拒绝。
- 检索器使用 exact-term guard 和最低相关度 `0.08`，因此成功的搜索也可能返回空列表；无证据时正向 claim 必须为空且分数不高于 30。
- Markdown 会在每条正向 claim 后显示引用，并附 `evidence ID -> relevance score -> 已清理 resume excerpt` 映射，方便人工核对来源。
- 普通 assistant 文本不能结束任务，只有 `submit_match_report` 的结构化参数通过校验才会生成报告。
- `max_steps`、`max_tool_calls`、`max_protocol_errors` 和模型超时限制失控循环；每次请求的 `max_completion_tokens`、累计 provider-reported total token 以及累计序列化上下文字符另有硬上限。

核心原则是：模型提出动作和候选结构，确定性代码负责权限、资源预算、引用来源与最终接纳。引用 ID 合法不等于 claim 已被 excerpt 语义蕴含；当前 runtime 也没有对技能差距、建议和面试题这些自由文本逐条做证据约束。

## 3. 可靠性设计

- Spring `AnalysisEngine` 端口解耦业务状态机与分析实现，支持 `agent` / `legacy` 配置切换。
- 原子 multipart 提交支持可选 `Idempotency-Key`：数据库只存 key 的 SHA-256 hash 和请求指纹；同 key/同指纹复用既有任务，同 key/不同指纹返回 409。前端在不修改输入的失败重试间复用 key。
- RabbitMQ 消息仍只传 `taskId`，worker 从 MySQL 重新读取正文，避免信任消息副本。
- outbox 事件有最大投递次数；达到上限进入不可自动认领的 `DEAD` 并暴露 counter/backlog。若任务仍为 `PENDING`，同一 publisher 事务将其标为 `FAILED_RETRYABLE/DELIVERY_FAILED` 且不自动定时重试，用户可调用现有 retry 接口重建 outbox；sweep 会收口已耗尽的旧行。这里的终态针对投递事件，不宣称消息链路 exactly-once。
- 调度器扫描超时的 `RUNNING`：未耗尽 attempts 时 guarded 恢复为 `PENDING` 并重投，耗尽时转为最终失败；worker 完成/失败落库还要求 attempt 匹配。该机制避免迟到结果覆盖新状态，但超时重试仍可能重复外部模型计算。
- Agent 2xx 响应仍由 Java 校验 task ID、0-100 分数范围和非空报告。
- Agent 网络错误、5xx、408 或 429 归为可重试失败；其他 4xx 和非法响应归为最终失败，避免无意义重试。
- `X-Agent-Token` 使用独立内部凭据，不复用浏览器/API token。
- correlation ID 跨 Java -> Agent 传递；分析任务对外失败消息由失败分类映射并清理/限长，outbox `lastError` 也清理控制字符/空白并限制长度；worker 与 Agent 生命周期失败日志只记录异常类型，不记录原始异常消息、简历、JD、prompt、工具参数或模型原文。
- Micrometer 增加 `agent.calls` 和 `agent.call.duration`，按 outcome 观察调用结果与耗时；成功日志另记录低基数模型名。
- Agent 响应提供 step、聚合 token usage、`providerReported` 和去参数化 tool trace，供离线评测，不扩大 Java 持久化的敏感数据面。provider 未返回完整 usage 时数字可能只是已报告部分，不能当作精确账单。

## 4. 评测设计

`agent-service/evals/cases.jsonl` 包含 5 个合成、非个人数据 case：

- 岗位与经历高度匹配。
- 前端经历投后端岗位的低匹配校准。
- 简历正文包含“忽略规则”等提示注入文本。
- 可靠异步工作流证据检索。
- 只有 RAG 关键词、缺少 Agent/eval 证据的差距识别。

评测器检查 score range、工具顺序、所有工具是否成功、逐条 claim 引用、引用到 excerpt 的映射、必需/禁止词、step 上限和延迟上限，并记录 token usage。它验证结构和来源，不执行 claim-to-evidence 语义蕴含判断。真实模型 pass rate 必须在配置 provider 后实际运行才能写入简历；仓库目前不伪造该数字。

## 5. 当前已验证事实

2026-08-03 当前分支的验证记录以覆盖范围为主；发布前应以第 8 节命令在目标 commit 上统一复跑并刷新数量：

- Python Agent：`pytest` 当前快照为 46 passed，覆盖工具协议、逐条引用/空检索、资源预算、HTTP 鉴权与 OpenAI-compatible adapter 组件贯通。
- React：ESLint、TypeScript typecheck、Vitest 与生产构建可作为门禁执行，覆盖运行时契约、提交幂等 key 和页面交互；精确数量待目标 commit 统一复跑，不在本节固化。
- Java：fast 与 Testcontainers 范围覆盖领域/用例/API、Flyway/MySQL、outbox、RabbitMQ、Redis 和后端 E2E；精确数量待目标 commit 统一复跑，不在本节固化旧数字。
- Playwright 同时保留确定性浏览器场景和 Compose full-stack 场景；主 Compose 与 E2E overlay 可做配置校验。
- Git 仓库根目录已提交 push/pull request CI 配置，含 Java fast/集成、Python、前端质量/构建和浏览器 E2E jobs；配置存在不能替代远程运行记录，当前不声称“CI 全绿”。
- 真实模型 eval runner 与 5 个合成 case 已就绪，但未提交 provider 运行结果或 pass rate。

## 6. 可直接用于简历的项目表述

项目名称建议写为：`AI 简历匹配 Agent｜个人项目｜核心开发者`。

- 设计 Java/Spring Boot 主系统 + Python/FastAPI Agent sidecar 架构，以 `AnalysisEngine` 端口支持 Agent/legacy RAG 双引擎切换，保留 MySQL 事务、outbox、RabbitMQ worker 与任务重试状态机。
- 实现有界 Tool Calling runtime，开放 JD 读取、简历证据检索、结构化报告提交 3 个白名单工具；通过 Pydantic schema、调用顺序、step/tool/error、completion/total token 与 context budget 约束模型行为。
- 构建带 exact-term/最低相关度过滤的本地检索，将正向内容收敛为逐条 claim + evidence IDs，拒绝未知引用并生成引用到片段映射；同时明确该校验不等于语义事实验证。
- 以幂等原子提交、transactional outbox、有限投递/`DEAD`、陈旧 `RUNNING` 恢复和 attempt 条件更新支撑异步 Agent；失败消息清理且生命周期日志不落原文或原始异常消息。
- 建立 5-case 合成 Agent eval 集与 Java/Python/React/Playwright 分层测试，覆盖工具顺序、引用结构、注入抵抗、资源预算及浏览器到 Agent 的跨进程契约；真实模型结果与远程 CI 状态均只在实际运行后报告。
- 接入内部 token、correlation ID、`agent.calls`/`agent.call.duration` 指标及网络/协议错误分类，使 Agent 调用可观测、可重试且可回退。

不能写成“实习经历”“生产日调用量”“线上准确率/降本比例”或“真实用户规模”，除非之后确实获得相应证据。

## 7. 技术面试高频追问

### 为什么拆 Python sidecar，不直接在 Java 写？

Java 已有可靠业务链路，重写会扩大事务和状态迁移风险；Python 更适合快速迭代模型协议、工具和 eval。边界只传一次分析请求/响应，sidecar 无状态，失败由 Java 状态机接管，因此拆分收益大于分布式复杂度。

### 为什么称为 Agent，而不是固定工作流？

工具集合和安全边界固定，但模型在预算内动态选择检索 query、调用次数和提交时机；固定代码只验证动作。若每一步都由代码预先决定，就只是 workflow。

### 如何避免幻觉写进报告？

不能承诺完全消除。这里做的是把可接受输出收窄：每条 `coreClaims` / `matchedSkills` 正向内容必须引用本轮非空检索返回的 evidence ID，未知 ID 被拒；空检索时正向 claim 为空且分数不高于 30；报告展示引用与原文映射。当前没有验证 claim 与 excerpt 的语义蕴含，技能差距、建议和面试题仍是自由文本，进一步应增加逐条 NLI/规则校验。

### 如何处理 prompt injection？

初始 prompt 不含正文；工具输出把正文显式标为 untrusted；模型无任意网络/代码执行工具；正文中的指令不能改变工具白名单、调用状态和 evidence 校验。评测集中包含注入 case。此方案降低风险，但不是绝对安全证明。

### 为什么不用向量数据库？

单份简历数据量很小，进程内稳定 hashing retrieval 足够验证工具协议和 grounding，部署成本更低。若扩展到跨人才库检索，可保持工具 schema 不变，把实现替换为 pgvector、Milvus 或 Elasticsearch，并补召回率/延迟评测。

### 模型超时或乱调工具怎么办？

网络/5xx 由 Java 标记 retryable，并沿既有调度和 outbox 重试；4xx/非法协议归为 final。Agent 内部对 step、工具数、协议错误数、请求时间、单次 completion token、累计 provider-reported token 和累计上下文字符设硬上限，超过预算立即失败，不允许无限循环。provider usage 不完整时还有独立的字符预算兜底。

## 8. 复现命令

```powershell
mvn test
mvn verify
Set-Location agent-service
.\.venv\Scripts\python.exe -m pytest
Set-Location ..
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend run test -- --run
npm --prefix frontend run build
npm --prefix frontend run test:e2e
npm --prefix frontend run test:e2e:full-stack
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example -f docker-compose.yml -f docker-compose.e2e.yml config --quiet
```

`mvn verify` 和 full-stack E2E 需要可用的容器运行时；真实模型评测还需要显式配置 provider。把命令的实际输出留作证据，不把 CI 配置、合成 case 或某次机器环境状态替代成远程全绿与真实模型效果结论。
