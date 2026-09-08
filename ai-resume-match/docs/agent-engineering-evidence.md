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
       -> explicit legacy Java RAG baseline
  -> report persistence / Redis cache / UI polling
```

Java 是业务主系统，拥有任务状态、事务、重试和最终报告；Python 只负责无状态 Agent 编排。模型不能直接访问数据库、文件系统或网络工具。

## 2. 为什么这不是简单工作流套壳

Agent 具备模型驱动的动作选择：模型可以决定每项 requirement 的检索 query、top-K、是否追加检索以及何时提交。但所有提取出的 requirement 都必须检索并评估，自主性被限制在明确的 capability 边界内：

- 只注册三个 allowlisted function tools，未知工具直接拒绝。
- 初始模型上下文除固定指令外只含 task ID；岗位标题、标签、简历和 JD 均不直接注入。
- JD 和检索片段由工具返回，并标记为 untrusted data，降低正文提示注入影响。
- 工具参数由 Pydantic 校验，拒绝未知字段、非法 JSON、越界长度和越界 top-K。
- 调用顺序由状态机校验：先读取 JD 并得到最多 5 条稳定 `requirementId/text/mustHave/weight`，再逐项检索，最后提交完整 assessments。
- 搜索参数必须带 requirement ID；证据按 requirement 记录。A 项检索出的 ID 不能直接支撑 B 项，除非 B 自己的搜索也返回该 ID。
- 模型提交每项 requirement 的候选 `supported/partial/not_found + explanation + evidenceIds`，不提交 matchScore/coreClaims/matchedSkills；独立 verifier 检查关键术语覆盖和邻近中英文否定表达，只能保留或降级候选状态。运行时再以固定 `1/0.5/0` 系数按权重算分，must-have 缺失时最高 69。
- 运行时从 assessments 派生正向 claim 和 gap；`supported/partial` 必须携带同项 evidence，`not_found` 不得引用。检索器使用 exact-term guard 和最低相关度 `0.08`，成功搜索也可能返回空列表。
- `match-report-v2` 同时返回 requirement breakdown、逐条 claim 引用、确定性 score breakdown 和仅包含最终引用的已清理 evidence；Markdown 由同一结果派生，仅作兼容展示。
- 普通 assistant 文本不能结束任务，只有 `submit_match_report` 的结构化参数通过校验才会生成报告。
- `max_steps`、`max_tool_calls`、`max_protocol_errors` 和模型超时限制失控循环；每次请求的 `max_completion_tokens`、累计 provider-reported total token 以及累计序列化上下文字符另有硬上限。

核心原则是：模型提出动作、status 和解释，确定性代码负责权限、资源预算、引用来源、规则验证、score 公式与最终接纳。`conservative-lexical-negation-v2` 能拦截显式否定和关键术语缺失，异常时 fail closed；但它尚未通过独立人工标注集校准，也不能完整理解同义/隐含语义。建议和面试题仍是自由文本。

## 3. 可靠性设计

- Spring `AnalysisEngine` 端口解耦业务状态机与分析实现，支持 `agent` / `legacy` 配置切换。
- 原子 multipart 提交支持可选 `Idempotency-Key`：数据库只存 key 的 SHA-256 hash 和请求指纹；同 key/同指纹复用既有任务，同 key/不同指纹返回 409。前端在不修改输入的失败重试间复用 key。
- RabbitMQ 消息仍只传 `taskId`，worker 从 MySQL 重新读取正文，避免信任消息副本。
- outbox 事件使用 capped exponential backoff + jitter，并有最大投递次数；达到上限进入不可自动认领的 `DEAD` 并暴露 counter/backlog。若任务仍为 `PENDING`，同一 publisher 事务将其标为 `FAILED_RETRYABLE/DELIVERY_FAILED` 且不自动定时重试，用户可调用现有 retry 接口重建 outbox；sweep 会收口已耗尽的旧行。终态 outbox 和安全可删除的幂等元数据按可配置保留期分批清理，关联活跃任务的幂等记录保留。这里的终态针对投递事件，不宣称消息链路 exactly-once。
- 调度器扫描超时的 `RUNNING`：未耗尽 attempts 时 guarded 恢复为 `PENDING` 并重投，耗尽时转为最终失败；worker 完成/失败落库还要求 attempt 匹配。该机制避免迟到结果覆盖新状态，但超时重试仍可能重复外部模型计算。
- Agent 2xx 响应由 Java 校验 task ID、0-100 分数、版本、requirement 权重计分、must-have cap、model/verifier/final status 单调性和完整 evidence 闭包；未知、重复、未验证或未引用 evidence 会被拒绝。
- Agent 网络错误、5xx、408 或 429 归为可重试失败；其他 4xx 和非法响应归为最终失败，避免无意义重试。
- `X-Agent-Token` 使用独立内部凭据，不复用浏览器/API token。
- correlation ID 跨 Java -> Agent 传递；可选 W3C trace context 还会持久化穿过 outbox、Rabbit producer/consumer、Java HTTP、FastAPI 和 chat/embedding HTTP，并把 trace ID 写入安全 provenance。分析任务对外失败消息由失败分类映射并清理/限长，outbox `lastError` 也清理控制字符/空白并限制长度；worker 与 Agent 生命周期失败日志只记录异常类型，不记录原始异常消息、简历、JD、prompt、工具参数或模型原文。
- Micrometer 增加 `agent.calls` 和 `agent.call.duration`，按 outcome 观察调用结果与耗时；成功日志另记录低基数模型名。
- Agent 响应提供 `structuredReport`、`requirementResults`、step、聚合 token usage、`providerReported`、`model/promptVersion/retrieverVersion/verifierVersion`、可空 trace ID、去参数化 tool trace 与 `agent-run-v1`。后者包含请求/运行时版本、task-scoped 长度前缀 SHA-256 输入指纹、Chat/Tool/总耗时、Chat 调用数与上下文字符；Java 使用同一跨语言测试向量独立重算指纹，并校验调用次数/工具耗时闭包。三项定价配置原子存在且 usage 完整时，Decimal 计算额外生成 `estimatedCostUsd/pricingVersion`；否则保持 `null`。Java 验证后以 `report_schema_version`、结构化 JSON 和 `analysis-run-v1` provenance 持久化，REST API 将其作为 JSON object 返回而非转义字符串；provenance 不含简历/JD、工具参数或隐藏推理，输入指纹也不进入日志/metrics/traces。估算值不能替代 provider 账单，指纹也不是加密或完整重放快照。
- 报告 REST 契约不再把 `structuredReport/provenance` 暴露为无边界 `JsonNode`。Java response-only DTO 生成显式 OpenAPI components，快照测试锁定引用、必填字段、enum 与指纹 pattern；前端生成类型与 Zod 通过 `satisfies` 和双向精确类型断言绑定。历史 trace/run/cost 缺字段会规范化为 `null`，其他损坏的必填存储字段不会被 API 静默输出。

## 4. 评测设计

无需模型凭据的 retrieval 层包含 12 份合成文档和 60 个 query，使用稳定 `documentId/spanId/start/end` qrels，按 chunk 与 gold span 的坐标重叠计算 Recall@5、MRR@5 和无证据误召回率。当前 hashing baseline 为 Recall@5 `0.8864`、MRR@5 `0.8636`、false-positive rate `0.0000`；5 个英文同义词 case 未召回。该结果只用于检索器回归对比，不等于人工标注的招聘质量结论。

`agent-service/evals/cases.jsonl` 是版本化的 `agent-live-eval-v4` 120-case 合成集，不含个人数据：

- 高匹配、部分匹配、完全无关/空证据和否定边界保留至少 10 条核心覆盖；无证据要求正向 claim 为空且 score `<= 30`。
- 简历 prompt injection 与 JD prompt injection 各 25 条，覆盖 role spoof、delimiter breakout、prompt/data exfiltration、伪造 observation/tool result 等攻击形状。
- 长文、格式噪声、时间范围、缩写/同义词、伪造 evidence ID 和协议形状输入 6 个专项切片各 5 条。
- 直接注入共 50 条；连同 forged-evidence/protocol-adversarial，用 `security` 标签可选择 60 条安全对抗集。每条攻击用例至少有一个 forbidden success marker，防止只统计 HTTP 成功。

评测器检查 score range、由 `requirementResults` 重算后的确定性 score、response/result verifier 版本一致性、工具顺序、所有工具是否成功、逐条 claim 引用、引用映射、必需/禁止词、空正向 claim、step 和延迟上限。`agent-live-eval-result-v3` runner 记录 dataset SHA-256、commit/dirty state、model/prompt/retriever/verifier 版本、temperature、分标签通过率、p50/p95、token usage、score 标准差、可用时的 trace ID，以及成本已估算 run 数、估算总额和全部 pricing versions。它验证结构、来源、版本和计分公式；规则 verifier 的真实误接收/误拒绝率仍必须由独立人工标注集给出。

## 5. 当前已验证事实

2026-08-13 当前工作树的验证记录以覆盖范围为主；发布前应以第 8 节命令在目标 commit 上统一复跑并刷新数量：

- Python Agent：当前工作树 `147 passed`，覆盖 requirement 提取/权重/确定性计分、同 requirement 证据绑定、中英文否定、混合正负证据、false-negation 反例、verifier 异常 fail-closed、hashing/dense/RRF hybrid、embedding provider 响应与取消校验、工具协议、逐条引用/空检索、qrels/检索指标、live runner、版本化成本估算/覆盖率、FastAPI/HTTPX W3C trace、探针排除与正文/token canary；该数量必须在目标 commit 上复跑后才可作为发布记录。
- React：ESLint、TypeScript typecheck、Vitest 与生产构建可作为门禁执行；运行时 Zod 会独立重算结构化分数并验证 evidence 闭包，Playwright 覆盖从正向结论打开证据抽屉的链路。精确数量待目标 commit 统一复跑，不在本节固化。
- Java：fast 与 Testcontainers 范围覆盖领域/用例/API、Flyway/MySQL、outbox、RabbitMQ、Redis 和后端 E2E；精确数量待目标 commit 统一复跑，不在本节固化旧数字。
- Playwright 同时保留浏览器路由 mock 场景和 Compose full-stack 场景；后者已在当前工作树以 `1 passed` 验证真实 Python Agent sidecar、动态三工具循环、Java 异步链路、V2 持久化/缓存、证据抽屉及 provenance。外部 chat provider 为无付费确定性替身，不作为真实模型质量证据。
- Git 仓库根目录已提交 push/pull request CI 配置，含 Java fast/集成、Python、前端质量/构建和浏览器 E2E jobs；发布前已在 GitHub Actions 远程运行中验证 5 个 jobs 全绿，其中浏览器 job 同时执行确定性 Playwright 与真实 Compose 全栈流程。
- 真实模型 eval runner 与 `agent-live-eval-v4` 的 120 个合成 case 已就绪；其中简历/JD 直接注入各 25 条，连同伪造证据和协议形状输入共 60 条安全对抗用例。仓库未提交 provider 运行结果或 pass rate，用例数不能当成安全成功率。

## 6. 可直接用于简历的项目表述

项目名称建议写为：`AI 简历匹配 Agent｜个人项目｜核心开发者`。

- 设计 Java/Spring Boot 主系统 + Python/FastAPI Agent sidecar 架构，以 `AnalysisEngine` 端口支持 Agent/legacy RAG 双引擎切换，保留 MySQL 事务、outbox、RabbitMQ worker 与任务重试状态机。
- 实现有界 Tool Calling runtime，开放 JD 读取、简历证据检索、结构化报告提交 3 个白名单工具；通过 Pydantic schema、调用顺序、step/tool/error、completion/total token 与 context budget 约束模型行为。
- 将 JD 规范化为带 ID/must-have/weight 的 requirement，逐项绑定检索证据；以版本化规则 verifier 检查术语覆盖和中英文否定，只允许保守降级，再用固定公式计分并限制缺失 must-have 的高分，同时明确该护栏不等于完整语义事实验证。
- 打通 `match-report-v2` 从 Pydantic、Java DTO/Flyway/MySQL 到 Zod/React 的版本化契约，持久化不含正文的模型/Prompt/Retriever/Verifier provenance，并提供 requirement/claim 到证据片段的交互式回查。
- 以幂等原子提交、transactional outbox、有限投递/`DEAD`、陈旧 `RUNNING` 恢复和 attempt 条件更新支撑异步 Agent；失败消息清理且生命周期日志不落原文或原始异常消息。
- 建立 60-query span/qrels 检索基线和版本化 120-case live Agent eval，以 Java/Python/React/Playwright 分层测试覆盖工具顺序、引用结构、60 条安全对抗、空证据硬约束、长文/格式/时效边界、资源预算及浏览器到 Agent 的跨进程契约；真实模型结果只在实际运行后报告。
- 接入内部 token、correlation ID、Java/Python Prometheus 与可选 W3C/OTLP tracing；trace context 跨 outbox/RabbitMQ/Agent/provider 延续并以 Tempo/报告 trace ID 回查，结构化网络/协议错误分类使可重试与最终失败边界可验证；legacy 只做显式基线，不宣称自动回退。

不能写成“实习经历”“生产日调用量”“线上准确率/降本比例”或“真实用户规模”，除非之后确实获得相应证据。

## 7. 技术面试高频追问

### 为什么拆 Python sidecar，不直接在 Java 写？

Java 已有可靠业务链路，重写会扩大事务和状态迁移风险；Python 更适合快速迭代模型协议、工具和 eval。边界只传一次分析请求/响应，sidecar 无状态，失败由 Java 状态机接管，因此拆分收益大于分布式复杂度。

### 为什么称为 Agent，而不是固定工作流？

工具集合、requirement 覆盖范围和安全边界固定，但模型在预算内动态选择每项检索 query、top-K、追加检索次数、status/解释和提交时机；固定代码验证动作并计算分数。准确定位是 bounded evidence-retrieval Agent，不是自主 Agent。

### 如何避免幻觉写进报告？

不能承诺完全消除。这里把可接受输出收窄为逐 requirement assessment：`supported/partial` 必须引用同项检索证据，规则 verifier 会拦截显式否定或关键术语缺失，异常按 `not_found` 处理；运行时派生 claim/gap 并固定公式算分。当前 verifier 不理解完整同义/隐含语义，下一步必须用人工标注基准测量误接收与误拒绝，再决定是否接入 NLI 或固定版本 LLM verifier。

### 如何处理 prompt injection？

初始 prompt 不含正文；工具输出把正文显式标为 untrusted；模型无任意网络/代码执行工具；正文中的指令不能改变工具白名单、调用状态和 evidence 校验。评测集中包含注入 case。此方案降低风险，但不是绝对安全证明。

### 为什么不用向量数据库？

单份简历数据量很小，索引仍放在进程内，无需为技术关键词引入向量数据库。默认 hashing 便于无凭据回归；显式 hybrid 可以用真实 provider embedding 补充同义召回，但必须通过同一 qrels A/B 后才切换默认。若扩展到跨人才库检索，可保持工具 schema 不变，再评估 pgvector、Milvus 或 Elasticsearch，并补 ANN 召回率、延迟和隔离测试。

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
