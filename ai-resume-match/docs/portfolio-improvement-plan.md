# AI Resume Match：AI 全栈简历项目改造方案

> 审查日期：2026-08-13\
> 审查基线：`9d9296c`\
> 目标岗位：AI 全栈开发 / AI 应用工程 / Agent 工程化\
> 本文只描述已核验现状和后续方案；所有未来指标必须实际跑出后才能写入简历。

## 1. 结论先行

这个项目已经明显超过“前端 + 后端 + 一次大模型调用”的普通作品：它有真实的多轮 Tool Calling Agent、逐条证据引用、异步状态机、transactional outbox、RabbitMQ、Redis cache-aside、幂等提交、失败恢复和完整测试分层。

当前最强的部分是**全栈工程闭环和异步可靠性**，主要短板不再是缺少中间件，而是下面四件事：

1. **AI 质量证据仍未闭环**：已补 60-query 检索基线、版本化 120-case live Agent 数据集和可配置 provider hybrid 实现，但默认仍是 hashing，尚未提交真实 embedding/model 基线、人工 claim 语义支持率、端到端延迟和成本结果。
2. **公网身份与费用治理仍是 Demo 级**：匿名签名会话、owner 隔离和窗口限流已落地，但仍没有 OIDC 账户、跨设备恢复、并发上限、每日 token/美元预算与正式租户治理。
3. **真实质量展示仍不完整**：README 已提供桌面/移动截图、约 69 秒录屏和无模型凭据的一键 Compose Demo，但仍没有公开在线实例或绑定干净 commit 的真实 provider 评测 artifact。
4. **生产质量边界仍需补证据**：结构化报告、删除/30 天保留、最小端口暴露和可观测性已落地，但文档解析仍无 OCR/病毒扫描，尚无完整负载测试、生产告警通知和 trace 租户治理。

推荐把项目定位为：

> **证据驱动的简历—岗位匹配 Agent：以 Spring Boot 承载事务与可靠异步流程，以 FastAPI 实现受限 Tool Calling 和评测，以 React 呈现可追溯的匹配结论。**

当前可以称为“工程化较强的 AI 应用/Agent 项目”；在完成真实评测、语义检索、确定性评分和结构化报告后，再强调“高质量 RAG”。

## 2. 当前能力审查

| 维度 | 当前状态 | 判断 |
| --- | --- | --- |
| 产品闭环 | 上传 PDF/DOCX、填写 JD、异步分析、历史筛选、失败重试、报告查看 | 已完成，适合作为全栈主项目 |
| Agent | 模型可动态决定检索 query、topK、调用次数和提交时机；3 个白名单工具 | 是真实但受限的 Agent，不是固定三步脚本，也不是自主多 Agent |
| Grounding | 正向 claim 必须引用本轮 evidence ID；无证据时限制高分 | 结构约束较强，但“引用合法”还不等于“语义蕴含” |
| RAG | 文本切分、hashing baseline、provider dense cosine、RRF hybrid、Top-K | 语义实现入口已成立，但未提交真实 provider A/B，默认仍是词法基线，不能宣称质量提升 |
| 后端可靠性 | MySQL 事实源、outbox、RabbitMQ confirm/return、有限重试、`DEAD`、attempt fence、陈旧任务恢复 | 是项目最有面试价值的部分，应保留并用故障实验证明 |
| 前端工程 | React、TanStack Query、Zod、表单校验、退避轮询、安全 Markdown、结构化报告/证据抽屉、响应式与错误边界 | 完整度高，下一步是可选在线 Demo 与真实质量结果展示 |
| 测试 | Java/Python/React/Playwright/Testcontainers/Compose 分层，含 60-query retrieval 与 120-case live Agent eval | 覆盖面强；下一步是人工 claim 标注、真实 provider 基线与完整负载测试 |
| 可观测性 | Java Micrometer + Python Prometheus、MySQL readiness、outbox age、provider/token/retrieval/tool/protocol/capacity/cost 指标、15-panel Grafana、本地规则、W3C/OTLP 与 Tempo | 指标、版本化成本估算和本地跨服务 trace 已落地；仍无账单对账、生产 trace 治理和通知渠道 |
| 安全与隐私 | 匿名会话 owner 隔离、窗口限流、删除/30 天保留、内容签名/解压边界、PII best-effort 脱敏、CSP 与安全 Markdown | 公共合成 Demo 边界较完整；真实多人服务仍需 OIDC、账户生命周期、费用预算和更强 PII/文件安全能力 |
| 作品集呈现 | README 含桌面/移动截图、无声 WebM 导览和一键无凭据 Demo，文档边界表述诚实 | 缺公开在线实例和真实 provider/人工质量 artifact |

### 2.1 值得保留的现有设计

- Java 负责事务、一致性和任务状态，Python Agent 保持无状态；无需为了“微服务”继续拆分。
- MySQL 是事实源、Redis 只做 cache-aside；无需把 Redis 改成业务事实来源。
- transactional outbox、guarded update、attempt fence 和有限重试已经落地；无需另做一套消息投递机制。
- Agent 的白名单工具、严格参数 schema、调用顺序、step/tool/error/token/context budget、未知 evidence 拒绝都是真实亮点。
- 前端已有 Zod 运行时契约、幂等 key 复用、AbortSignal、安全 Markdown、可访问性基础和路由懒加载。
- README 主动声明没有真实流量、SLA、准确率和“消除幻觉”结论，这种克制应继续保留。

### 2.2 本次本机验证基线（2026-08-18）

| 命令 | 结果 | 备注 |
| --- | --- | --- |
| `mvn test` | 312 tests passed | 含 V1→V9、100 组反事实输入、telemetry 静态门禁、Java 日志 canary、Prometheus endpoint/readiness、W3C HTTP/outbox/Rabbit trace、跨语言输入指纹、显式 OpenAPI 报告契约与 provenance 滚动兼容 |
| `mvn verify` | 本轮未执行 | Docker daemon 未启动；不能用 H2 fast tests 代替真实 MySQL/RabbitMQ/Redis 结论。最近一次已记录基线为 260 fast + 16 Testcontainers IT |
| `.venv\Scripts\python -m pytest` | 150 passed | 含 Agent、检索、verifier、eval runner、版本化 Decimal 成本、运行元数据/输入指纹、Prometheus 低基数标签、FastAPI/HTTPX trace 父子关系/探针排除/正文 canary 与取消终态 |
| `npm run lint` | passed |  |
| `npm run typecheck` | passed |  |
| `npm run test` | 23 files / 224 tests passed | 含成本/trace/run metadata 字段约束、旧 provenance 滚动兼容与报告展示 |
| `npm run build` | passed | 生产构建成功 |
| `npm run test:e2e` | 5 passed / 3 skipped | 确定性浏览器流程通过；full-stack、作品截图与录屏由各自显式命令执行 |
| 四组 `docker compose config --quiet` | passed | 基础、Demo、full-stack E2E 与 observability overlay 均可解析；Prometheus/Grafana/Tempo YAML 和 dashboard JSON 也已解析，不代表容器已启动 |
| `npm run test:e2e-protocol` | 3 passed | 动态 requirement 搜索与 V2 提交协议通过 |
| `npm run test:e2e:full-stack` | 1 passed | 真实 Python Agent sidecar + 无付费确定性 chat provider 的跨进程链路通过 |

本次没有运行真实模型 eval，因此不能据此声称匹配准确率、真实模型通过率、p95 延迟或单次成本。

### 2.3 已观察到的两个工程问题

1. `src/test/resources/application.yml` 配置了 `spring.task.scheduling.enabled: false`，但 `AiMatchApplication` 直接使用 `@EnableScheduling`。本次 `mvn test` 仍出现 `scheduling-1` 与 H2 的锁超时/回滚异常堆栈，说明测试调度没有被可靠关闭。
2. `mvn verify` 虽然成功，但 Testcontainers 的 `MountableFile` 线程多次出现 `NoSuchMethodError: SystemProperties.getUserName(String)`。依赖树显示 `poi-ooxml 5.5.1 -> commons-compress 1.28.0 -> commons-lang3 3.14.0`，需要统一二进制兼容版本并增加依赖收敛门禁。

这两个问题应先处理，否则“测试全绿”的截图会夹带明显异常栈，削弱作品可信度。

## 3. 改造原则

1. **先测量，再替换**：先建立 hashing baseline，再引入语义检索；所有升级都要能说明提升了什么。
2. **结构化数据是事实，Markdown 是视图**：Agent 返回的结构化结果应跨 Python、Java、MySQL、API 和 React 保留下来。
3. **公网 Demo 与本地作品分开设计**：本地作品可不做完整多租户；一旦公网接收真实简历，身份、隔离、限流和删除必须升为 P0。
4. **展示可复现证据**：测试数量、eval、延迟、token、成本和故障恢复都要绑定 commit、模型和数据集版本。
5. **控制范围**：不为了技术关键词增加 Kubernetes、多 Agent、MCP 或向量数据库；只有评测或使用场景证明需要时再引入。

## 4. P0：下一轮最高优先级

当前版本已经可以作为简历项目，但更适合强调“Agent 工程化与可靠异步系统”。如果要把它作为主打的“AI 全栈”项目，建议先完成 **P0-Core：P0-1、P0-2 的首版基线、P0-4、P0-5**。P0-3 是由评测结果驱动的质量升级：先证明 hashing 的瓶颈，再决定 embedding、hybrid 和 verifier 的实现，不把技术替换本身当作完成标准。

P0-Core 各任务估算合计 11–17 个工作日，建议计入联调缓冲后按 12–18 个工作日排期；全部 P0（包含 P0-3、不含公网条件项）的任务估算合计为 15–23 个工作日。

### P0-1 清理质量基线与配置契约

**目标**：让每次测试输出干净、Agent 成为默认展示主链路，超时和错误分类一致。

修改内容：

- 把 `@EnableScheduling` 移到带条件的配置类，例如由 `analysis.scheduling.enabled` 控制；测试 profile 显式关闭。
- 对齐 `commons-compress` / `commons-lang3` 兼容版本，并用 Maven Enforcer 做 dependency convergence/upper-bound 检查。
- 将默认 `analysis.engine` 从 `legacy` 改为 `agent`；legacy 只作为显式 baseline 开关，不描述为自动 fallback。
- 生产 profile 对 engine、Agent URL、token、数据源和消息服务配置 fail closed；使用 `@ConfigurationProperties + @Validated` 统一校验。
- 修正时间预算：Python 当前可能执行 `45 秒 × 6 steps`，Java read timeout 只有 60 秒，而 eval 可接受 90 秒。增加单次分析全局 deadline，并让单步模型 timeout 取“剩余预算与单步上限”的较小值。
- 定义 Agent 错误契约 `{code, retryable, retryAfterSeconds}`；协议/参数/预算错误进入 final，provider 网络错误、408/429/5xx 才重试。

主要文件：

- `src/main/java/com/zhulikang/aimatch/AiMatchApplication.java`
- `src/test/resources/application.yml`
- `src/main/resources/application*.yml`
- `pom.xml`
- `agent-service/agent_service/main.py`
- `agent-service/agent_service/config.py`
- `src/main/java/com/zhulikang/aimatch/application/analysis/AgentServiceAnalysisEngine.java`

验收标准：

- `mvn test` 的 253 个测试通过，且无后台调度数据库锁/回滚异常。
- `mvn verify` 的 8 个 IT 实际执行并通过，且无 `NoSuchMethodError`。
- prod 缺少任一关键配置时启动测试失败；不会静默选择 legacy。
- 全局 Agent deadline 小于 Java read timeout，并有跨服务配置测试。
- 每类 Agent 错误都有 Java/Python contract test；确定性协议错误不重复消费模型。

预计工作量：1–2 天。

#### P0-1 实施状态（2026-08-13）

本轮已完成核心改造：

- [x] 将调度能力移到 `SchedulingConfiguration`，由 `analysis.scheduling.enabled` 控制；测试配置显式关闭，并增加“无 scheduled processor”上下文测试。
- [x] 将 `commons-lang3` 收敛到 3.18.0，保留 `commons-compress` 1.28.0，并增加针对两者的 Maven upper-bound 门禁。
- [x] Java 与 Compose 默认使用 `agent`；`legacy` 仅在显式配置时装配。prod 的 engine、Agent URL 和 token 改为无默认值占位符。
- [x] 增加 50 秒整次 Agent deadline，并固定默认预算为 `45s provider < 50s Agent < 60s Java`；Python 配置校验和 Java 跨服务默认值测试同时覆盖该约束。
- [x] 增加 `{code, message, retryable, retryAfterSeconds}` 错误响应；Java 以结构化 `retryable` 为准，并通过与 HTTP 状态相反的测试样例验证 payload 优先级。
- [x] 更新 README、Agent 说明、架构和运维文档，删除“自动 fallback”歧义。

实测结果：

| 验证 | 结果 |
| --- | --- |
| `mvn test` | 247 passed；无 `scheduling-1`、`JdbcSQLTimeoutException` |
| `mvn verify` | 247 fast + 6 integration passed；无 `NoSuchMethodError` |
| `python -m pytest -q` | 51 passed |
| `docker compose --env-file .env.example config --quiet` | passed |
| 依赖树 | `commons-compress 1.28.0`、`commons-lang3 3.18.0` |

仍保留两个小项，不阻塞进入 P0-2：

- [x] 将 27 处自定义 `@Value` 收口为 `AiProperties/AgentProperties/ApiProperties/ReportProperties/AnalysisProperties/ResumeProperties` 六组 `@ConfigurationProperties + @Validated`；保留原配置键并生成 IDE 配置元数据。参数化上下文测试覆盖 43 个必填叶子逐项缺失和 28 个非法值/跨字段预算，包括 URL、secret、正数、Agent timeout、outbox/AI task retry jitter 与 DOCX 聚合资源边界，全部在启动阶段 fail fast。
- [x] AI 任务重试从固定延迟升级为 capped exponential backoff + bounded jitter；Java 将 Agent 的 `retryAfterSeconds` 端到端传入任务策略并作为最短等待时间，同时以本地 `ANALYSIS_RETRY_MAX_DELAY` 截断异常长建议值。策略测试覆盖 attempt 增长、溢出、jitter 边界、非法上游值和上限。

2026-08-18 配置收口复验：`mvn clean test` 为 380 tests、0 failure/error/skip；生成的 Spring 配置元数据包含当时全部 41 个叶子。Python 为 151 passed，前端为 224 passed，lint/typecheck/OpenAPI drift/build 和 Compose config 均通过。本机 Docker daemon 当时未运行，因此 `mvn verify` 的 16 个 Testcontainers IT 全部按既有 `disabledWithoutDocker` 策略跳过；该次命令不作为新的容器集成通过证据，仍保留此前有 Docker 环境下的验证记录。后续 AI task retry 策略批次将叶子扩为 43 个；其最新完整回归结果记录在同一节后续条目。

2026-08-18 AI task retry 复验：`mvn clean test` 为 390 tests、0 failure/error/skip，生成元数据包含 43 个自定义配置叶子且主代码保持 0 处自定义 `@Value`。Python 为 151 passed；前端 lint/typecheck/OpenAPI drift/build 与 224 tests 通过；基础、Demo、E2E 三组 Compose config 通过。Docker daemon 未运行，因此本批未重复执行 Docker-backed Testcontainers/全栈浏览器 E2E，不把 Compose 静态校验写成容器运行通过。

2026-08-18 Agent capacity 复验：Python 全量为 156 passed，新增并发测试在容量 1 下保持首请求阻塞，证明第二请求得到结构化 503 且 provider 总调用仍为 1；取消首请求后 semaphore 与 in-progress gauge 都归零。Java 全量仍为 390 tests、0 failure/error/skip；基础与 observability Compose 可解析，dashboard JSON 为 15 panels，容量告警 YAML 已由部署配置测试解析。Docker daemon 仍未运行，本条同样不声称容器运行通过。

2026-08-18 claim—evidence 人工评测工具链复验：Python 全量为 165 passed；其中 13 个定向测试覆盖盲化与 prediction key 隔离、task hash 防篡改、双人完整覆盖/身份分离、精确分歧仲裁、Cohen's kappa、误接收/误拒绝指标及 Wilson 95% 区间、零分母 fail-closed、只提取最终引用证据，以及失败/缺失/错序时不写敏感 source。CLI artifact 记录样本量与质量门禁，CLI 与测试模块通过 `compileall`。这只证明评测基础设施可执行；尚未进行真人盲标，也未产生可对外引用的人工质量数字。

同批跨栈回归：`mvn clean test` 为 390 tests、0 failure/error/skip；前端 lint、typecheck、OpenAPI drift、224 tests 和 production build 通过；基础、Demo、E2E、observability 四种 Compose 组合静态解析通过，`git diff --check` 无 whitespace error。Docker daemon 仍未运行，因此没有重复执行 Testcontainers 或 Compose 浏览器运行测试。

### P0-2 建立可发布的 AI 评测基线

**目标**：从“有 eval 代码”升级为“有版本化、可复现、可解释的质量结果”。

修改内容：

- 将最初 5 个 case 扩展到 100–120 个脱敏/合成 case，覆盖：
  - 高、中、低匹配和完全无关；
  - 中文、英文、中英混合、缩写和同义词；
  - 否定表达、时间范围、技能只出现在项目标题但无经历；
  - 空证据、伪造 evidence ID、简历/JD prompt injection；
  - 长简历、重复内容、格式噪声和边界长度。
- 定义可计算 retrieval 指标的数据集 schema：
  - 每份文档有稳定 `documentId`，人工标注的原文 span 有稳定 `spanId/start/end`；不直接把易随切分策略变化的 `resume:0` 当 gold key。
  - 每个 retrieval case 包含 `query`、`relevantSpanIds` 和显式 `noRelevantEvidence`；两者必须互斥。
  - retriever 返回 chunk 的 source offset；chunk 与任一 gold span 重叠时计为 relevant，从而允许公平比较不同 chunker。
  - Recall@5 在所有 `relevantSpanIds` 非空的 query 上按 `|retrieved relevant spans ∩ gold spans| / |gold spans|` 做 macro average；MRR@5 使用首个 relevant 结果的倒数排名；false-positive rate 的分母是全部 `noRelevantEvidence=true` case，其中任何非空返回都计一次 false positive。
- 把评测拆为三层：
  - retrieval：Recall@K、MRR@K、不相关查询 false-positive rate；
  - grounding：citation coverage、citation validity、unsupported positive claim rate；
  - end-to-end：结构通过率、分数校准、稳定性、p50/p95、token 和估算成本。
- 保存 `commit/model/promptVersion/retrieverVersion/verifierVersion/datasetVersion/temperature/time`，提交脱敏 `baseline.json` 和 Markdown 汇总。
- PR 中只跑无凭据确定性 eval；真实 provider eval 使用手动或定时 workflow，并把 artifact 与目标 commit 绑定。
- 失败样例要分类展示，不能只汇总 pass rate。
- unsupported-claim 人工评测使用独立标注说明，将 claim—evidence 对标为 `supported/partial/unsupported`；首版评估至少 100 条正向 claim（不足时评估全部），由两名标注者独立判断并对分歧仲裁，同时报告样本量和一致性。

建议的首版门槛：

- 工具顺序违规：0。
- 正向 claim 的引用覆盖率与 ID 可解析率：100%。
- 空证据场景分数 `<= 30`：100%。
- 越权工具或伪造 ID 成功数：0。
- retrieval Recall@5 `>= 0.90`、MRR@5 `>= 0.80`、不相关查询 false-positive `<= 5%`。
- 人工抽检 unsupported positive claim rate `<= 2%`。
- 同一输入重复 3 次，分数标准差 `<= 3`；若暂时不达标，真实记录基线而不是隐藏失败。

主要文件/目录：

- `agent-service/evals/`
- `agent-service/agent_service/evaluation.py`
- `agent-service/tests/test_evaluation.py`
- `../.github/workflows/ci.yml`
- 建议新增 `docs/evals/` 保存脱敏结果与解释

预计工作量：3–5 天，人工标注质量比 case 数量更重要。

#### P0-2 实施状态：检索基线切片（2026-08-13）

本轮已完成无需模型凭据的 retrieval 子层：

- [x] 新增 12 份合成文档、60 个 query 的 `retrieval-qrels-v1`：44 个正例、16 个显式无证据例，覆盖中英混合、同义词、否定、长文本、多 span 与无关查询。
- [x] gold 使用稳定 `documentId + spanId + start/end`；case 强制 `relevantSpanIds` 与 `noRelevantEvidence` 互斥。
- [x] hashing retriever 返回规范化正文的 `sourceStart/sourceEnd`；评测器按 retrieved chunk 与 gold span 的坐标重叠计分，不依赖 `resume:N`。
- [x] 实现 Recall@K、MRR@K、false-positive rate 和本地 p95；结果 artifact 不包含 query、简历正文或 excerpt。
- [x] 提交机器可读 baseline、Markdown 失败分类和确定性数据集生成器；数据集 hash 使用 canonical JSON，不受 Windows/Linux 换行影响。
- [x] PR 的 Python job 增加无凭据门禁：Recall@5 `>= 0.88`、MRR@5 `>= 0.86`、false-positive rate `= 0`。

当前 hashing baseline：

| 指标 | 结果 | 首版目标 | 判断 |
| --- | ---: | ---: | --- |
| Recall@5 | 0.8864 | >= 0.90 | 未达到，差 1.36 个百分点 |
| MRR@5 | 0.8636 | >= 0.80 | 达到 |
| 无证据 false-positive rate | 0.0000 | <= 0.05 | 达到 |
| 失败分类 | 5 个英文同义词 case 未召回 | 无固定值 | 明确暴露词法检索边界 |

当前数字只能描述这份合成 retrieval 回归集。baseline 记录了生成时的 base revision 和 `workingTreeDirty=true`；正式发布简历数据前，应由合并后 CI 重新生成绑定目标 commit 的 artifact。

P0-2 当前已完成的 live Agent 评测能力：

- [x] 将 5 个 live Agent case 扩充为 `agent-live-eval-v4` 的 120-case：保留高匹配、部分匹配、无证据、否定/边界核心切片，简历/JD 注入各扩至 25 条，并新增长文、格式噪声、时效边界、缩写/同义词、伪造 evidence 与协议形状输入 6 个专项切片；canonical builder 与 SHA 测试防止 JSONL 漂移。
- [x] 无证据用例增加硬断言：正向 claim 集必须为空且 score `<= 30`；结构评测同时要求工具顺序、成功 outcome、逐 claim 引用映射和版本元数据。
- [x] Agent 响应暴露 `model/promptVersion/retrieverVersion/verifierVersion`；runner 绑定 dataset SHA-256、commit/dirty state、temperature，支持 `--tag`、`--repeat`、p50/p95、token 汇总、分标签通过率与 score 标准差门槛。
- [x] runner artifact 不复制简历/JD、evidence excerpt、tool args、内部 token 或 provider 错误正文；数据集测试同时固定 120 条总量、50 条直接注入、60 条安全对抗、专项切片数量和每条攻击成功标记的 forbidden assertion。
- [x] 建立 `claim-evidence-*-v1` 人工评测输入/标注工具链与 `claim-evidence-eval-result-v2` 指标 artifact：显式导出仅含最终引用片段的敏感 source，拆分盲标 task set 与协调者 prediction key，校验双人完整独立标签、精确分歧仲裁与 task hash，并计算一致性、Cohen's kappa、三分类 confusion matrix、unsupported-positive-claim rate、false-rejection rate 和 Wilson 95% 区间；零分母 fail-closed，artifact 保存实际样本/质量门禁且不含正文、备注、理由或人员 ID。流程与边界见 `docs/claim-evidence-human-eval.md`。

P0-2 尚未完成的部分：

- [ ] 尚未由两位真人完成至少 100 个 accepted prediction 与 20 个 rejected prediction 的独立盲标并由第三人仲裁，因此当前没有可引用的人工一致性、unsupported-positive-claim rate 或 false-rejection rate；工具和合成测试通过不等于该质量门槛达标。
- [ ] 运行真实 provider eval，记录 model、prompt、temperature、token、成本、p50/p95 和三次重复稳定性。
- [x] live 集已达到 120 条：长文、格式噪声、时间范围、缩写/同义词、伪造 evidence ID 和协议形状输入均有专门切片；简历/JD 直接注入共 50 条，加上伪造证据/协议对抗共 60 条安全样本。内部 malformed tool response/protocol failure 继续由确定性单元/API 故障测试覆盖，不把提示词样本冒充 provider 协议故障。
- [ ] 在合并后生成 `workingTreeDirty=false` 且绑定目标 commit 的发布 artifact。

### P0-3 升级为可度量的混合检索与确定性评分

**目标**：解决当前 hashing 检索无法理解同义词、缩写和跨语言的问题，同时避免让模型自由决定 0–100 分。

修改内容：

- 抽象 `Retriever` 接口，保留 hashing 实现作为 baseline。
- 新增多语种 embedding，并与关键词/BM25 做 hybrid retrieval；只有离线评测证明有收益时再增加 reranker。
- chunk 保存 `section/page/charStart/charEnd/contentHash`，让前端能够定位原文并缓存 embedding。
- 单份简历仍可使用内存索引；只有扩展为跨简历人才库时才引入 pgvector/Milvus/Elasticsearch。
- 先将 JD 解析为带 ID、must-have、weight 的 requirement 列表，再逐 requirement 检索。
- 输出每项 requirement 的 `supported/partial/notFound + evidenceIds`，由确定性代码按权重计算总分；模型只负责提取和解释。
- 抽象 `EvidenceVerifier`，以独立 claim—evidence 标注集比较规则、NLI 和固定版本 LLM judge 后选择实现。首版若使用 LLM judge，只输入 claim 与引用片段，要求严格输出 `supported/partial/unsupported + confidence`；仅接受 `supported && confidence >= 0.80`，超时、非法结果和低置信度均 fail closed。它是运行时护栏，不作为自身正确性的 ground truth。
- verifier 使用至少 100 条独立人工标注 pair 校准；目标为误接收 unsupported claim `<= 2%`、误拒绝 supported claim `<= 10%`。若达不到，应保留人工评测结论并降低宣传范围，不用模型自评替代人工基准。

验收标准：

- 新检索器在同一标注集上相对 hashing baseline 的 Recall@5 提升至少 15–20 个百分点，或达到预设绝对门槛。
- warm retrieval p95 `<= 200 ms`，并记录 embedding 成本与缓存命中率。
- 无证据高分场景为 0；单条弱证据不能产生满分。
- 正向 claim 必须通过 evidence ID 与 `EvidenceVerifier` 双重校验；验收结果来自独立人工标注集。
- 同一结构化 requirement 输入产生确定性总分，模型重试不会任意改变计分公式。

主要文件：

- `agent-service/agent_service/retrieval.py`
- `agent-service/agent_service/tools.py`
- `agent-service/agent_service/models.py`
- `agent-service/agent_service/agent.py`
- 新增 retrieval/score eval 与 fixtures

预计工作量：4–6 天。

#### P0-3 实施状态：确定性计分与 hybrid 检索实现切片（2026-08-13）

本轮先完成不依赖新模型或虚构质量提升的结构层：

- [x] 确定性 requirement extractor：先解析 JD 条款及其必需/加分标记，补充未被条款覆盖的 skill tags；按硬性要求优先选择最多 5 项，生成稳定 `requirementId/text/mustHave/weight`。评分对应选中的要求，不等同于完整长 JD 的穷尽覆盖。
- [x] `search_resume_evidence` 强制携带 requirement ID，并按 requirement 保存可用 evidence；跨 requirement 挪用引用会被拒绝。
- [x] `submit_match_report` 不再接受模型提供的 score/coreClaims/matchedSkills；要求完整提交 `supported/partial/not_found + explanation + evidenceIds`，漏搜、漏评、重复评估、未知 ID 和状态—引用冲突均 fail closed。
- [x] 代码按固定系数 `supported=1 / partial=0.5 / not_found=0` 与 requirement weight 计算总分；must-have 为 `not_found` 时最高 69。API 新增 `requirementResults`，Markdown 新增逐项判定并保留旧 claim/evidence 展示兼容。
- [x] 新增 `EvidenceVerifier` 接口和 `conservative-lexical-negation-v2`：按 requirement 关键术语覆盖率判定，并识别 `did not use/without/no experience` 与 `未使用/尚未接入/没有经验` 等邻近否定；混合正负证据最多为 partial。
- [x] verifier 只允许保留或降级模型状态；异常 fail closed 为 `not_found`，最终响应记录 `modelStatus/status/verifierVersion/termCoverage/reason/accepted evidenceIds`，再重新计算总分。
- [x] live evaluator 从 `requirementResults` 独立重算 score 并校验 verifier 版本一致；Prompt 版本为 `requirement-verified-agent-v3`，live dataset 已升级为 `agent-live-eval-v4`，runner 汇总 verifier 版本。
- [x] 单测覆盖中英文否定、混合正负证据、`no downtime`/`无状态服务` false-negation 反例、verifier 异常脱敏与 fail-closed；精确数量以目标 commit 全量复跑为准。
- [x] 扩展 `Retriever`/`EmbeddingClient` 契约，新增 OpenAI-compatible embedding adapter；provider 输入批处理，响应按 index 重排，并拒绝缺失、非有限、零向量和维度漂移。
- [x] 新增 dense cosine + hashing 的 normalized RRF hybrid；文档向量与重复 query 仅在单次分析内缓存，不将简历正文写入共享向量库。
- [x] `RETRIEVER_MODE=hashing|hybrid` 显式切换，默认保持 hashing；hybrid 要求 endpoint/key/model，embedding timeout 小于分析 deadline，provider I/O 不阻塞事件循环，失败不会静默改变为另一检索语义。
- [x] retrieval runner 支持真实 hybrid provider、同 dataset hash/Top-K 的 hashing baseline 对比，以及 Recall/MRR lift、false-positive-rate increase 门禁；fake embedding 只用于单测，不生成质量结论。

仍未完成：

- [ ] `status/explanation` 仍由模型提出，规则 verifier 仅覆盖词项与显式否定；盲标、双人一致性、仲裁和指标工具已落地，但仍必须用至少 100 个真人标注 pair 测量误接收/误拒绝，再决定规则、NLI 或固定版本 LLM verifier，不能把规则护栏等同于语义正确。
- [ ] 当前 requirement extractor 依赖上游 skill tags 或规则 clause，尚未有人标注的 requirement parsing 指标；Java 的 known-tag 列表也仍有限。
- [ ] 尚未使用授权的真实多语种 embedding provider 生成 A/B artifact，因此默认未切到 hybrid；当前唯一可报告质量结果仍是 hashing Recall@5 `0.8864`、MRR@5 `0.8636`、false-positive rate `0.0000`。
- [ ] 当前 lexical 分支是 hashing/exact-term，不是 BM25；reranker、跨运行 content-hash embedding cache、section/page metadata 与 warm p95/成本指标仍待真实评测后决定。
- [x] Java/数据库/React 已消费 `match-report-v2`：结构化 requirement/claim/evidence/score breakdown 与安全 provenance 贯穿持久化、API、Zod 和证据抽屉；Markdown 仅作兼容视图。详见 P0-4 实施状态。

### P0-4 打通结构化报告、运行溯源与证据交互

**目标**：让 Python 已有的结构化结果贯穿后端和前端，形成真正可演示的 AI 产品。

当前损失链路：

`ReportSubmission -> render_report(Markdown) -> AgentAnalysisResponse(matchScore, reportMarkdown) -> MatchReport(reportContent) -> ReactMarkdown`

修改内容：

- 定义版本化 `MatchReportV2`：
  - `schemaVersion`
  - `requirements[]`
  - `coreClaims[]`
  - `matchedSkills[]`
  - `skillGaps[]`
  - `recommendations[]`
  - `interviewQuestions[]`
  - `evidence[]`
  - `scoreBreakdown`
- 增加 `AnalysisRunProvenance`：model、prompt/retriever/verifier/request/runtime version、task-scoped input fingerprint、steps、tool trace、provider usage、stage latency、estimated cost、correlation/trace ID；不得保存模型思维过程或未清理正文。dataset version 只属于 eval artifact，不伪造到普通用户报告。
- Java 不再丢弃 Python 返回的 `modelUsage/toolTrace`，并通过 Flyway 持久化结构化 JSON/规范化字段。
- Markdown 只作为兼容导出或由结构化结果派生，不再是唯一事实。
- 前端改为：总分与覆盖率、requirement 分解、匹配/差距卡片、建议、面试题、可展开 evidence drawer；点击 claim 能定位引用片段。
- 使用 OpenAPI 或共享 schema 生成/校验 TypeScript contract，避免 Java DTO 与手写 Zod 长期漂移。

验收标准：

- Python、Java DTO/API 和 Zod 验证同一 `schemaVersion`；MySQL 以独立非空版本列加 JSON 原文持久化，Flyway/仓储测试验证支持版本及可迁移性。除非显式增加数据库 JSON Schema 约束，不把“数据库能保存 JSON”表述为语义校验。
- 100% 正向 claim 至少引用一个本轮 evidence ID；未知 ID 在边界层被拒绝。
- 浏览器 E2E 验证一条 `requirement -> claim -> evidence excerpt` 完整链路。
- 390px、平板和 1440px 布局无横向溢出，键盘可完整展开/关闭证据。
- Markdown 中 HTML、远程图片和危险协议仍不可执行。
- 单次分析的 token、模型、耗时和工具阶段可查询，但不暴露隐藏推理或 PII。

主要文件：

- `agent-service/agent_service/models.py`
- `agent-service/agent_service/tools.py`
- `src/main/java/.../AgentServiceAnalysisEngine.java`
- `src/main/java/.../AnalysisResult.java`
- `src/main/java/.../MatchReport.java`
- `src/main/resources/db/migration/`
- `frontend/src/shared/api/schemas.ts`
- `frontend/src/features/reports/MatchReport.tsx`

预计工作量：5–7 天。

#### P0-4 实施状态：结构化报告纵向切片（2026-08-13）

- [x] Python 新增 `match-report-v2`，包含 requirement decisions、派生的 core claims/partial matches、skill gaps、建议、面试题、最终引用 evidence 及确定性 `scoreBreakdown`。响应顶层 score/requirements 必须与结构化图一致。
- [x] 结构化 evidence 只保留最终 decision/claim 实际引用的片段；Pydantic 拒绝未知、重复或未引用 ID，不把未使用的检索结果扩大到持久化数据面。
- [x] Java DTO 对 score、权重公式、must-have cap、model/verifier/final status 单调性、verification evidence 子集和完整 evidence 闭包做独立校验；不是只信任 Python JSON。
- [x] Flyway `V5__add_structured_match_report.sql` 增加非空 `report_schema_version`、结构化报告和 provenance 列；旧行默认 `markdown-v1`，实体/Redis/API 保持向后读取兼容。
- [x] Java 将 model、prompt/retriever/verifier/pricing version、steps、provider usage、估算成本、去参数化 tool trace、correlation ID 与可空 trace ID 保存为 `analysis-run-v1`；不保存简历/JD、工具参数、模型正文或隐藏推理。
- [x] Python 增加 `agent-run-v1`：请求/运行时/指纹版本、task-scoped 长度前缀 SHA-256、Chat provider 调用/耗时、工具/总耗时和累计上下文字符；Java 独立重算指纹并验证 calls/steps 与 tool duration/trace 闭包，前端 Zod 再验证并展示。Java/Python 共享硬编码测试向量，旧 provenance 缺字段时仍可滚动读取。
- [x] live eval 单条产物保留安全 `runMetadata`，汇总 Chat/Tool/Agent duration p50/p95、调用次数与 context chars p95；dataset version/SHA-256 保持在 eval artifact 顶层，不混入普通报告。
- [x] REST API 将持久化 JSON 解析为嵌套对象返回。前端 Zod 再独立验证版本、状态单调性、score 公式和 evidence 闭包，避免错误数据进入渲染层。
- [x] React 展示 requirement 状态/权重/覆盖率、core claim、partial match、差距、建议、面试题和运行版本；requirement/claim 的 evidence button 可展开已清理片段、检索分数和字符 offset。Markdown 收入兼容折叠区。
- [x] 确定性 Playwright 场景已覆盖提交分析、等待成功、打开 core claim 引用片段并返回历史；390px 与 1440px 响应式场景复用结构化 V2 fixture。
- [x] Compose full-stack 场景使用真实 Python Agent sidecar，仅将外部 chat provider 替换为无付费确定性实现；模型按动态 requirement ID 逐项检索并提交 assessments，Playwright 验证 100 分 V2 报告、证据抽屉、model/prompt provenance 与历史回查。基础设施仅在 Compose 内网暴露，前端使用 Docker 动态回环端口，失败时自动输出关键服务日志。

P0-4 尚未完成的部分：

- [ ] provenance 已包含版本化估算成本、跨 Java/Python/provider trace ID、Chat/Tool/Agent 分阶段耗时和输入一致性指纹；dataset version 已在 eval artifact 顶层而不属于普通报告。尚缺 embedding provider 独立计费/耗时归因与真实账单对账，token/估算仍不能视为账单，输入指纹也不等于确定性重放。
- [x] 报告 API 不再以 `JsonNode` 模糊声明结构化报告/provenance；response-only Java wire DTO 生成完整 requirement/evidence/score/run metadata OpenAPI schema，Spring 上下文精确快照锁定 `$ref`、required、enum 与 pattern。前端生成 TypeScript 类型后，Zod 使用 `satisfies` 和双向精确类型断言建立编译期门禁；CI 串联 Java 快照、`api:check` 和 typecheck。
- [ ] Java Testcontainers E2E 的分析引擎仍显式使用 legacy；真实 Agent 跨进程路径现已由 Compose 浏览器场景覆盖，但外部 chat provider 仍为确定性替身，不能据此声称真实模型质量或 provider 兼容性已经验收。
- [ ] 正向引用在结构上实现 100% coverage，但“片段是否在语义上蕴含 claim”仍需 P0-2 的独立人工标注集给出 precision，不能用 evidence ID 合法性替代质量结论。

### P0-5 降低招聘方体验成本

**目标**：让招聘方在 60 秒内理解项目，在 5 分钟内跑起来。

修改内容：

- 重写仓库根 README 首屏，直接放置：一句话价值、桌面/移动截图、60–90 秒 GIF/视频、架构图、实际验证结果、边界声明。
- 基于现有 `docker-compose.e2e.yml` 抽出独立 `docker-compose.demo.yml`，使用合成数据和 deterministic mock AI；一条命令启动，不要求付费模型 key。
- Demo 页面增加“一键填入合成简历/JD”，明确标注演示模式。
- Dashboard 展示已有 API 已返回但 UI 未利用的“处理中”和“可重试失败”，并可跳转到筛选后的历史页。
- 成功页增加“再次分析”、结构化 JSON/Markdown 导出和打印友好视图。
- 新增 `docs/case-study.md`，按“问题—决策—证据—局限”组织；把历史计划文档标记为 archive，降低阅读噪声。
- 添加 3–4 个 ADR：Python sidecar、outbox、Redis cache-aside、为什么单简历暂不使用向量数据库。

验收标准：

- 不 clone 仓库也能从 README 看到完整主流程和证据交互。
- 干净环境一条命令可启动无真实 key 的 Demo；数据 100% 为合成数据。
- README 中所有测试/评测数字都能链接到具体 commit 的 artifact 或文档。
- 录屏同时展示成功路径和至少一个可重试失败路径。

预计工作量：2–3 天。

#### P0-5 实施状态：作品展示首版（2026-08-13）

- [x] 新建分析页增加明确标注的“一键填入合成示例”：浏览器内生成结构有效的 PDF，填入不含真实个人/公司信息的 JD，不读取本机文件；用户手动修改后移除“当前为原始合成示例”的状态提示。
- [x] 新增独立 `docker-compose.demo.yml`。无需真实 provider key 即可启动 Nginx、Spring、MySQL/outbox、RabbitMQ、真实 Python Agent、Redis 与前端；仅 chat provider 为确定性本地替身。
- [x] Demo 基础设施只在 Compose 内网暴露，前端绑定 `127.0.0.1`；可用 `DEMO_PORT` 改端口。CI 对主 Compose、Demo overlay 和 E2E overlay 做合并配置校验。
- [x] 组件测试验证 PDF 结构/xref 和表单填充，普通 Playwright 使用合成入口；Compose full-stack Playwright 使用同一入口验证生成的 PDF 能被真实 Java 解析并进入 Agent V2 报告链路。
- [x] 独立 Demo overlay 已实际启动验证：7 个服务全部 healthy，`/frontend-health` 返回 `200`，仅前端映射到宿主回环地址；验证后已通过 `down -v` 清理合成数据。
- [x] 成功报告增加白名单化 JSON、兼容 Markdown 下载和打印入口；JSON 显式标记不包含源简历/JD，但保留最终引用片段和安全 provenance。移动端操作改为全宽，打印样式隐藏导航、任务元数据和交互按钮。
- [x] 新增 `docs/case-study.md`，按问题、架构、关键决策、Agent 收敛、异步恢复、验证证据、局限和简历表述边界组织面试叙事。
- [x] Dashboard 增加永久“体验合成演示”CTA，以 `/analyses/new?demo=1` 深链自动填充一次合成数据；成功页增加“再次分析”，明确创建新任务、返回空白表单且不复用上一份浏览器文件。
- [x] README 增加桌面 Dashboard、桌面证据报告和移动 Demo 截图；新增 `npm run capture:portfolio`，通过确定性同源 mock 可重复生成，且明确不把截图分数当作模型质量结论。
- [x] 新增 68.8 秒、1280×720 的无声 WebM 作品录屏，依次展示 Dashboard、合成提交、异步完成、逐项 requirement、证据回查、运行溯源、可重试失败与重新入队；`npm run capture:portfolio-video` 可重复生成，README 以报告截图作为观看入口。

P0-5 作品展示首版已完成。若后续公开托管仓库，可把 WebM 同步到 GitHub Release 或作品站 CDN，避免不同代码托管预览器对仓库内视频的支持差异；这不阻塞当前本地作品交付。

## 5. 公网部署条件项

如果项目只作为本地/录屏作品，完整 OAuth、多租户和公网基础设施不是当下最高优先级；如果要公开接收真实用户简历，则以下全部升为 P0。

### 5.1 用户身份、数据归属与配额

- 公开作品 Demo 使用服务端签发的 HMAC-SHA256 `HttpOnly + SameSite=Strict` 匿名会话；正式账户模式再替换为 Spring Security + OIDC/JWT。
- 给 resume、job、task 和幂等记录增加 owner；所有历史、汇总、详情、报告缓存读取前检查和重试查询都必须带 owner 条件。报告沿 task owner 继承归属。
- 幂等 key 先按 owner 重新 SHA-256；数据库继续只保存 64 位 hash，因此不同会话相同原始 key 不冲突且无需保存 bearer。
- 分析创建、原子提交和重试按会话执行 Redis Lua 原子限次；超限返回 429 且不进入任务创建/provider 链路，Redis 故障时付费入口失败关闭。
- Nginx 仍注入共享 token，但它现在只证明请求来自可信网关，不再作为业务用户身份；Agent service token 只用于内部服务身份。

验收：A 无法读取、重试或删除 B 的任务；两用户使用同一幂等 key 不冲突；跨用户 E2E 进入 CI。

#### 5.1 实施状态：匿名 Demo 身份与模型额度保护（2026-08-13）

- [x] `ApiTokenInterceptor` 先以常量时间校验网关 token，再解析或签发带过期时间的匿名会话 Cookie；未授权请求不会获得 Cookie，伪造/过期 Cookie 会被轮换。
- [x] Cookie bearer 不进入 bundle、Web Storage 或数据库；`owner_id` 只保存随机 session id 的 SHA-256，Flyway V8 为 resume、job、task、幂等表回填 legacy owner 并建立 owner 查询索引。
- [x] 上传、创建 JD/任务和原子提交写入当前 owner；列表、状态详情、汇总平均分、报告（在 cache lookup 之前）和手动重试全部按 owner 查询。跨 owner 使用与不存在相同的 404/空结果，避免枚举泄露。
- [x] 幂等 key 保存为 `SHA-256(ownerId + ':' + SHA-256(rawKey))`；原有全局唯一索引因此成为 owner-scoped opaque hash 的并发仲裁点，不需要存储原始 key，也不会发生跨会话冲突。
- [x] Redis Lua 以一个原子操作完成 `INCR + 首次 PEXPIRE + PTTL`；默认每会话 10 分钟 5 次。超限返回 `429` 和向上取整的 `Retry-After`，Redis 故障返回 503，controller 测试证明创建用例未被调用。
- [x] OpenAPI 快照声明付费入口的 429/503，Compose 与 `.env.example` 暴露 session TTL、Secure Cookie 和限流配置；命令行 runbook 使用 cookie jar 保持多步 flow 身份。
- [x] 268 个 fast tests 全绿；新增会话签名/轮换、Redis fail-closed、JPA 跨 owner 列表/详情/汇总/缓存前检查测试。真实容器 E2E 已改为浏览器式 cookie jar，并断言第二会话对第一会话的 task/report 得到 404、历史为空。
- [ ] 当前匿名身份不支持登录、跨设备恢复或账户找回；真正公网长期服务仍需 OIDC subject、账户生命周期与 owner 数据迁移。
- [x] Python Agent 用可配置 `BoundedSemaphore` 限制单进程同时运行的已授权分析；容量等待默认 0.1 秒，耗尽时在 chat/embedding provider 调用前返回 `503 AGENT_CAPACITY_EXHAUSTED + retryAfterSeconds=1`。并发取消测试证明第二请求不产生 provider call、首请求释放容量且 in-progress 归零；Prometheus/Grafana/本地告警覆盖上限、在途和拒绝。
- [ ] 当前仍没有跨 Agent 副本的全局并发上限、日 token/美元预算账本或可信代理 IP 防滥用；单进程 semaphore 不能替代这些公网成本治理能力。

### 5.2 PII、删除与保留策略

- [x] Flyway V9 删除与 `raw_text` 重复的 `resume.structured_summary`，Java 领域模型与上传链路不再复制第二份正文；旧环境升级前仍应备份并确认该列未被私自改作其他用途。
- [x] 增加 owner-scoped 删除 API 与前端二次确认；事务清理 MySQL 报告、幂等/outbox、任务和无共享引用的简历/JD，并驱逐 Redis 报告缓存。运行中任务删除后，迟到 worker 结果由 task/attempt guard 拒绝落库。
- [x] `ANALYSIS_USER_DATA_RETENTION` 默认 30 天；定时任务按 `created_at` 清理所有超过截止时间的任务，并在行锁内再次校验时间。活跃状态同样清理，避免匿名会话过期后形成无法访问的永久数据；迟到结果由已有 fence 拒绝。
- 上传前说明模型提供方、数据用途和保留时间；公共 Demo 默认只允许合成数据或短 TTL。
- [x] 在调用任何 AI 引擎前，对邮箱、常见电话、中国居民身份证号及带标签的姓名、地址、受保护属性做确定性 best-effort 脱敏；仅记录分类计数，不记录命中值。
- [x] 100 组仅改变带标签姓名、性别和年龄的反事实输入，在脱敏边界后得到完全相同的 provider-bound `AnalysisInput`。这证明当前规则覆盖范围内不会把这些原值送入模型，不等于真实模型输出公平。
- 产品明确标注“仅提供求职辅助分析，不能替代人工招聘决策”，不将受保护属性用于计分。

验收：用户两步内删除分析，相关缓存和数据库记录在限定时间内消失；日志/trace 扫描不包含简历/JD 正文。

#### 5.2 实施状态：数据最小化、删除与保留闭环（2026-08-17）

- [x] `DELETE /api/analysis/{taskId}` 仅删除当前匿名 owner 的数据；跨 owner 与不存在保持同一 404，接口返回 204 且不回显已删除内容。
- [x] 删除顺序满足外键约束：report、idempotency、outbox → task → 无剩余 task 引用的 resume/job；共享来源在最后一个任务删除前保留。
- [x] `ReportCache.evict` 对 Redis 故障保持 best-effort，并以 `report.cache.evictions{outcome}` 观测；数据库仍为删除事实来源。
- [x] worker 完成仍先执行 taskId/status/attempt 条件更新，已删除运行中任务更新数为 0，因此不会保存迟到报告；队列中的重复或迟到消息也无法重新创建任务。
- [x] 前端数据管理区提供不可恢复说明、二次确认、运行中请求边界提示、安全错误/request ID 与成功后 query cache 清理。
- [x] 新建页在上传前说明模型数据流、30 天默认保留与主动删除入口，并优先引导公开 Demo 使用合成数据。
- [x] `ModelInputPrivacySanitizer` 位于 Java worker 到 `AnalysisEngine` 的共同边界，Agent 与 legacy 都不会收到已识别的原值；`analysis.model.input.redactions{type}` 提供无正文的命中计数，单元测试保护技术版本号/年份范围不被误删。
- [x] V9 前向迁移删除重复正文列；H2 MySQL-mode 的 V1→V9 升级测试断言列已消失，领域模型只保留 `rawText`。
- [x] `SensitiveTelemetryPolicyTest` 对 Java/Python 生产源码中的日志、指标 tag 和 trace attribute/event 做 source-level 检查；Java worker 与 Python Agent 成功链路再用正文 canary 验证捕获日志不泄漏。门禁禁止正文 getter/变量和裸异常进入自有 telemetry。
- [x] 100 组受保护属性反事实测试验证脱敏后的完整 provider 输入一致，而不是只比较单个字段。
- [ ] 当前脱敏仍是规则型纵深防御，未覆盖无标签姓名、所有地址格式、图片 PII 或多语种实体；source-level telemetry 门禁与 span canary 不是 AST/DLP，也未扫描第三方库或真实生产 collector 中的完整数据面；真实 provider 输出公平性仍需标注集和统计检验，不能宣称完整隐私合规或招聘公平。

### 5.3 网络与文件摄取

- 单独维护生产部署 overlay，只暴露 HTTPS 网关；Agent、MySQL、Redis、RabbitMQ 与 Actuator 走 internal network。
- [x] readiness 已显式包含 `readinessState` 与 MySQL `db`，Redis 故障仍可有界回源；生产部署仍需把 Actuator 移到私有管理面并加访问控制。
- 文件校验扩展名、magic bytes 和 MIME 一致性；限制 PDF 页数、DOCX 解压体积/entry 数、抽取时间和内存。
- 补 DOCX 表格、页眉、页脚和列表抽取；扫描件明确提示 OCR 不支持。病毒扫描作为公网模式可插拔能力。

#### 5.3 实施状态：文件摄取资源边界（2026-08-17）

- [x] PDF 在前 1,024 bytes 检查 `%PDF-`，DOCX 检查 ZIP local header 并要求标准 `[Content_Types].xml`、`word/document.xml`；扩展名伪装在解析前拒绝，不信任客户端 MIME header。
- [x] `RESUME_MAX_PDF_PAGES=50`；DOCX 默认最多 512 entries、单 entry 解压 10MB、累计解压 20MB，扫描在 POI 解析前完成并拒绝路径穿越 entry。
- [x] DOCX 按 body element 顺序抽取普通段落和表格单元格，保留段落边界以改善检索切分和带标签 PII 识别。
- [ ] 页眉/页脚、图片 OCR、恶意文件扫描、解析 wall-clock deadline 与沙箱隔离仍未实现；公网真实文件摄取前必须继续补齐。

## 6. P1：增强高级工程证据

| 编号 | 改进项 | 核心验收 |
| --- | --- | --- |
| P1-1 | Prometheus + Grafana + OpenTelemetry | Java/Python Prometheus、p95、outbox、token/retrieval/tool/protocol、版本化成本估算、本地规则与 W3C/Tempo 跨服务 trace 已完成；剩余账单对账、生产 trace 治理和通知闭环 |
| P1-2 | 异步链路故障注入 | 已完成：broker 断开/恢复、重复消息、Agent 超时、worker 认领后终止状态、Redis 故障回源，以及 confirm 后、结果落库前终止与 lease 恢复均有真实基础设施测试 |
| P1-3 | outbox/lease 演进 | 两阶段核心实现已完成：短事务 + lease fencing、capped exponential backoff + jitter、终态 outbox/幂等元数据安全清理；剩余负载与真实停机演练 |
| P1-4 | 真实 MySQL 并发测试 | 已完成：双事务幂等、双 Publisher/Worker 竞争均在 MySQL 8.4/RabbitMQ 下验证；允许 at-least-once，但只保留一个有效报告 |
| P1-5 | API 契约 | 已完成：创建任务返回 `202 + Location`，已处理错误统一 Problem Details 并带 request ID，OpenAPI 快照生成/校验前端类型 |
| P1-6 | 前端作品级门禁 | axe critical/serious 为 0；桌面/平板/移动视觉回归；键盘完成提交、轮询、报告和证据展开 |
| P1-7 | CI/供应链 | JaCoCo/pytest/前端 coverage 趋势、Dependabot/Renovate、SBOM、依赖/镜像漏洞扫描、secret scanning |
| P1-8 | 产品效率 | 历史关键词/日期/排序、URL 保留筛选、任务阶段与 elapsed time、取消/再次分析、报告导出 |

这些任务用于把“设计正确”升级成“有可观察、可复现的故障证据”。下一批测试应优先投入真实数据库并发、故障注入、授权和隐私，而不是继续堆普通 mock 单测。

### P1-1 第一至四阶段实施状态：Prometheus、成本估算、本地告警与分布式追踪（2026-08-18）

- [x] 引入 Micrometer Prometheus registry，开放 `/actuator/prometheus`，所有指标带 `application=ai-resume-match` 公共标签；worker、Agent 和 legacy AI 耗时启用 histogram，可聚合查询 p95。
- [x] readiness group 显式包含 `readinessState` 与 `db`，测试同时锁定 MySQL 参与、Redis cache-aside 不参与。
- [x] 新增 `analysis.outbox.oldest.age`，通过数据库 `min(createdAt)` 聚合查询计算 `PENDING/FAILED/PROCESSING` 最老事件秒龄，不加载 outbox LOB payload；无积压返回 0，未来时钟偏移也钳制为 0。
- [x] Python Agent 暴露独立 `/metrics`：整次分析/provider/tool duration 与 outcome、in-progress、reported token、retrieval hit/empty/evidence、协议拒绝、鉴权失败和最终 requirement status。所有标签值均收敛到固定枚举，不记录正文、query、ID、异常消息或动态版本名；ASGI 取消路径显式落为 `cancelled` 并归零 in-progress gauge。
- [x] 成本估算要求定价版本、输入单价和输出单价原子配置，使用 Decimal 按完整 provider usage 计算 8 位小数 USD；缺配置或 usage 不完整时返回 `null` 并记录固定 skip reason。Java、Zod 和滚动兼容测试独立验证成对字段，前端展示价格版本，live eval v3 汇总估算覆盖率、总额和全部 pricing versions。
- [x] 新增可选 `docker-compose.observability.yml`，Prometheus/Grafana 仅绑定 `127.0.0.1`；Prometheus 同时抓取 Java/Agent，预置 15-panel operations dashboard 与两端 target down、outbox、任务/provider 失败率、Agent 协议/容量错误和价格已启用但 usage 缺失规则。
- [x] Spring 集成测试真实请求 `/actuator/prometheus` 并断言指标名/公共标签；配置测试解析 Compose、Prometheus/Grafana YAML、告警规则和 dashboard JSON。
- [x] Java 使用 Micrometer-OTel bridge，Rabbit template/listener 开启 observation；API traceparent 与 correlation ID 一起写入 outbox，publisher 恢复远程父上下文后继续 Rabbit producer/consumer 与 Spring `RestTemplate` span，解决异步事务边界丢链。
- [x] Python 使用独立 `TracerProvider`、ParentBased ratio sampler、FastAPI/HTTPX instrumentation 与 OTLP/HTTP exporter；chat 与 embedding 客户端都延续上游上下文，响应、Java provenance、前端和 live eval artifact 可携带 trace ID。
- [x] observability overlay 新增仅回环暴露的 Tempo、24 小时本地保留和 Grafana Tempo 数据源；Java/Python 默认 tracing 关闭，overlay 显式开启。测试覆盖 W3C 父子关系、Spring HTTP header、outbox/Rabbit header、chat/embedding provider 传播、滚动兼容、非法配置/ID和正文/token canary。
- [ ] 尚未完成 provider 账单对账、Alertmanager 通知、生产 trace 访问控制/租户隔离、保留/删除策略与真实负载下的采样调优；版本化估算仍不能替代 provider 账单，不能把本地栈表述成生产观测闭环。

### P1-5 实施状态：HTTP 与前后端契约（2026-08-13）

- [x] `/api/analysis` 与 `/api/analysis-submissions` 返回 `202 Accepted`、任务快照和指向状态资源的 `Location`，表达“任务已接受而非已完成”。
- [x] 认证、参数校验、业务错误、资源不存在和幂等冲突等已处理错误统一返回 `application/problem+json`；标准字段之外保留稳定 `code`、可空 `requestId` 与迁移期 `message`。
- [x] 新增 Springdoc 规范与 `X-API-Token` security scheme；`api/openapi.json` 由真实 Spring 上下文生成，精确快照测试还锁定关键 `202`、`Location` 和 Problem Details schema。
- [x] 前端用固定版本生成器从规范生成 TypeScript 类型，Zod 错误 schema 以生成的 `ApiProblemDetail` 为编译期约束，同时兼容滚动部署中的旧错误形状。
- [x] CI 在前端质量任务中重新生成并检查差异；`prod` profile 默认关闭 `/v3/api-docs`，需要时才显式开放。
- [x] 生成器依赖兼容当前 TypeScript 6，`npm audit` 为 0 项漏洞。
- [x] 完整 `mvn verify` 通过 260 个 fast tests + 16 个 Testcontainers IT；前端 216 个组件/契约测试、生产构建与 5 个有效 Playwright 场景通过。

### P1-2 实施状态：broker、worker、Agent 与 Redis 故障注入（2026-08-13）

- [x] `AnalysisOutboxPublisherIT` 使用容器内 `rabbitmqctl stop_app` 关闭真实 RabbitMQ broker application；publish 失败后 outbox 保持可恢复的 `FAILED`，`attemptCount=1`、`nextAttemptAt` 和清理后的错误信息被持久化，不会误写 `PUBLISHED`。
- [x] 测试在 `start_app` 后等待连接恢复，并用偏移时钟越过退避窗口；同一事件成功重投、错误字段清空，队列仅收到 1 条有效消息。
- [x] `AnalysisPublisherWorkerConcurrencyIT` 启动真实本地 HTTP server，收到 Agent 请求后故意不响应；250ms Java 读超时会沿实际 `AgentServiceAnalysisEngine -> RunAnalysisUseCase -> AnalysisTaskService` 路径将任务落为 `FAILED_RETRYABLE/AI_UNAVAILABLE`。
- [x] 超时任务保持 `attemptCount=1` 和 `nextRetryAt`，用户错误文案不含底层网络异常，且不会持久化半成品报告；`agent.calls{outcome="failure"}` 记录一次失败。
- [x] 同一 IT 模拟 worker 在原子认领后终止留下的 `RUNNING/attempt=1` 持久状态；越过 15 分钟 lease 后，恢复事务将其重置为 `PENDING` 并创建新 outbox，真实 RabbitMQ 重投使第二次 attempt 成功，最终只有 1 份报告。
- [x] `EndToEndAnalysisFlowIT` 先删除缓存、再 pause 真实 Redis 容器；缓存读写各以 500ms 超时失败，报告接口在 3 秒内从 MySQL 返回完整结果。unpause 后通过同一业务接口重新写入缓存。
- [x] 主配置增加 `REDIS_CONNECT_TIMEOUT`/`REDIS_COMMAND_TIMEOUT`（默认 500ms），把“捕获缓存异常”补成有响应时间边界的真实降级能力。
- [x] `AnalysisOutboxPublisher` 在 broker ACK 和 `PUBLISHED` 结果短事务之间提供包内生命周期 seam；测试在该精确位置抛出 `Error`，Rabbit 消息保持已确认，先前认领短事务留下 `PROCESSING + leaseToken + leaseUntil`。
- [x] 恢复 publisher 后同一 event 被再次发送，队列可观察到 2 条同 task 消息；依次交给 worker 后 AI 引擎只调用 1 次、task 为 `SUCCESS/attemptCount=1`、报告只有 1 行。
- [x] 最新完整 `mvn verify` 实际复跑通过：260 个 fast tests + 16 个 Testcontainers IT。
- [x] P1-2 已完成。测试采用精确 ACK 后终止故障点而不是启动子 JVM 再由操作系统 kill，并验证租约过期后的持久恢复语义。结论仍是 at-least-once，不声称 exactly-once。

### P1-4 实施状态：真实 InnoDB/RabbitMQ 并发竞争（2026-08-13）

- [x] 新增 `IdempotentAnalysisSubmissionMySqlIT`，使用 MySQL 8.4 + Flyway + Hibernate `ddl-auto=validate`，不以 H2 的锁和唯一约束行为替代生产数据库语义。
- [x] 在 mock 简历准备器中加入双请求屏障；只有两个请求都完成首次幂等查询并进入准备阶段后才同时放行，从而稳定触发唯一索引竞争。
- [x] 相同 key + 相同 payload：两个调用都成功并返回同一 task ID；仅持久化 1 份 resume、JD、task、outbox 和幂等记录。
- [x] 相同 key + 不同 payload：恰好一个调用成功，另一个得到 `IdempotencyConflictException`；竞争失败事务不遗留孤儿业务数据。
- [x] 只持久化 SHA-256 key hash 和 request fingerprint，不把原始 key、岗位内容或简历文本写入幂等表。
- [x] 新增 `AnalysisPublisherWorkerConcurrencyIT`：获胜 publisher 在事务外 Rabbit 发送点由屏障暂停，另一个 publisher 能在 confirm 等待期间完成，证明不被长数据库事务阻塞；最终仅 1 次 `convertAndSend`、队列仅 1 条消息，事件进入 `PUBLISHED`。
- [x] 同一 task 的普通投递与 redelivery 由两个 worker 同时处理：获胜 worker 在 AI 引擎内暂停时，另一 worker 已完成并跳过；最终引擎调用 1 次、task 为 `SUCCESS`、`attemptCount=1`、报告仅 1 行。
- [x] 当前完整 `mvn verify` 基线为 260 个 fast tests + 16 个 Testcontainers IT；新增场景自动进入现有 GitHub Java integration job。
- [x] P1-4 已完成。这里证明的是当前原子认领与唯一报告约束，不把 at-least-once 系统夸大为 exactly-once；confirm 后终止窗口已由 P1-2 覆盖，publisher 短事务/lease fencing 已在 P1-3 第一阶段落地。

### P1-3 两阶段实施状态：outbox 短事务、lease fencing 与生命周期治理（2026-08-13）

- [x] Flyway V6 为 `analysis_outbox` 增加可空 `lease_token varchar(36)`、`lease_until timestamp(6)` 与 `(status, lease_until, id)` 索引；升级时把旧 `PROCESSING.next_attempt_at` 迁移为显式 lease deadline，H2/MySQL 8.4 migration 测试同时校验。
- [x] publisher 不再用一个事务包住整个批次。候选扫描、逐事件 guarded claim、成功/失败/中断落库分别使用 `REQUIRES_NEW` 短事务，Rabbit send 与 confirm/return 等待完全位于事务外。
- [x] 每个事件只在即将发送前认领，写入 UUID token 和独立 lease deadline；避免批次首条阻塞或中断时提前占用后续事件。
- [x] 成功、失败和中断结果都要求 `id + PROCESSING + leaseToken` 匹配。租约过期被新实例重领后，旧 token 的迟到结果会被忽略；真实 MySQL 仓储测试覆盖过期重领和旧 token fencing。
- [x] lease duration 默认 30 秒并要求严格大于 Rabbit confirm timeout；配置遗漏或边界非法时应用拒绝启动。中断会释放当前租约且不增加 attempt。
- [x] MySQL 8.4 + RabbitMQ 测试在获胜实例阻塞于事务外 send 时确认竞争实例可完成；ACK 后终止测试确认持久状态为 `PROCESSING`，越过 lease 后可重发，而 downstream worker 仍只生成一个报告。
- [x] 新增独立 `OutboxRetryPolicy`：默认从 30 秒开始按失败次数翻倍，限制在 15 分钟内，并支持 `0..1` 可配置对称 jitter；测试覆盖次数增长、上限、随机边界、整数极值和非法配置。
- [x] Flyway V7 增加 `terminal_at`、终态扫描索引和幂等创建时间索引；旧 `PUBLISHED/DEAD` 行在迁移时补齐终态时间，H2 与真实 MySQL 8.4 migration 均通过。
- [x] retention scheduler 默认每小时、每类最多 200 行，清理超过 30 天的 `PUBLISHED/DEAD` outbox，以及未绑定任务或仅关联终态任务的幂等记录；`PENDING/RUNNING/FAILED_RETRYABLE` 关联记录通过查询与删除双重 guard 保留。
- [x] 清理行为暴露 `analysis.retention.deleted{resource=outbox|idempotency}`；配置在 application、Compose 与 `.env.example` 三处对齐。Spring scheduler 停机时最多等待 10 秒完成当前批次。
- [x] H2 事务测试验证分批边界和两轮清理；真实 MySQL 8.4 IT 验证终态删除、近期记录保留、活跃任务幂等保护和计数指标。
- [x] 完整 `mvn verify` 实际复跑通过：260 个 fast tests + 16 个 Testcontainers IT，failure/error/skip 均为 0。
- [ ] 当前未实现 heartbeat；token fencing 能阻止 lease 过期后的迟到写覆盖，但不能阻止网络调用本身继续。默认保持 `5s confirm < 30s lease`；若连接建立或未来 claim 内工作可能接近 lease，应先收紧连接超时或实现 token 条件续租。
- [ ] 在可重复负载下记录 publish latency、数据库锁等待、retry 分布和清理吞吐；再执行真实 `SIGTERM/docker stop` 停机演练。完成这些运行证据后再把 P1-3 标为全部完成。

## 7. P2：有数据证明需要时再做

- OCR/扫描 PDF。
- 同一份简历对多个岗位的并排比较、版本差异和技能趋势。
- 用户反馈闭环、模型/Prompt A/B、质量漂移监控。
- 跨简历人才库与 ANN benchmark；只有这个场景才值得引入 pgvector/Milvus 等向量数据库。
- 国际化、暗色模式、复杂图表、批量导入和招聘方协作。
- 更复杂的模型路由、缓存和批处理优化。

不建议仅为简历关键词加入：Kubernetes、更多微服务、MCP、多 Agent、任意代码执行工具或第二套消息系统。它们会扩大攻击面和维护成本，却不直接改善当前最弱的 AI 质量证据与展示效果。

## 8. 推荐实施路线

### 最小简历增强版（P0-Core）：约 12–18 个工作日

1. 质量基线清理：调度隔离、依赖兼容、主链路/超时/错误契约。
2. 60+ case eval、稳定 span/qrels 定义与 hashing baseline，提交真实脱敏结果。
3. 结构化报告贯穿 Python、Java、数据库与 React，完成 claim—evidence 交互。
4. 根 README、截图/录屏、无凭据 Demo 和 case study。

完成这一版后，项目的“AI 全栈”说服力会显著高于再增加一种中间件。

### 完整四周版

| 周次 | 目标 | 交付物 |
| --- | --- | --- |
| 第 1 周 | 基线可信 | 干净测试输出、Agent 默认主链路、统一 deadline/错误契约、版本化 eval 数据集和 hashing baseline |
| 第 2 周 | AI 质量 | hybrid retrieval、requirement-centric 输出、确定性计分、claim 支持校验、对比报告 |
| 第 3 周 | 产品化 | MatchReportV2、provenance、Flyway/API/Zod、证据抽屉、报告分解、E2E |
| 第 4 周 | 可展示与可运维 | 无凭据 Demo、截图/录屏、README/case study、隐私删除或合成数据限制、dashboard、至少 2 个故障实验 |

依赖关系：

```text
干净基线
  -> 版本化评测与 hashing baseline
      -> hybrid retrieval / 确定性评分
          -> 结构化报告与证据 UI
              -> Demo、截图、简历指标

公网发布
  -> 身份与 owner 隔离
  -> 限流与费用预算
  -> 删除/保留/隐私说明
  -> 网络隔离与文件摄取加固
```

## 9. 每阶段 Definition of Done

### AI

- 有带版本的 eval 数据集、运行配置、原始结果和失败分类。
- hashing 与新检索器使用同一数据集对比。
- 每条正向结论可追溯到 evidence，且不仅验证 ID 存在，还验证语义支持。
- 计分规则可解释、可重复，模型不能凭一条弱证据自由给满分。
- latency/token/cost 指标来自实际运行，不使用估算宣传。

### 后端

- `mvn test` / `mvn verify` 输出无后台异常栈。
- Python—Java 错误分类与 deadline 契约一致。
- 结构化报告和 provenance 原子持久化，schema 可迁移。
- 公网模式下所有资源有 owner，删除与 retention 可验证。
- 至少有 RabbitMQ 故障与 Agent 超时两个可复现恢复实验。

### 前端

- 招聘方能看到 requirement 分解和 claim—evidence 交互，不只是 Markdown。
- 主流程在桌面、平板、移动端和键盘操作下通过。
- 安全 Markdown、CSP、错误边界和请求取消能力不回退。
- Demo 使用合成数据，并明确提示模式和隐私边界。

### 作品集

- README 首屏包含截图/GIF、架构、三项核心亮点、验证结果和边界。
- 一条命令启动无凭据 Demo，或提供稳定在线 Demo 与 60–90 秒视频。
- `docs/case-study.md` 能回答：为什么这样设计、失败时怎样恢复、AI 质量如何测、当前有什么局限。
- 所有简历数字都能追溯到仓库 artifact；没有“生产级”“消除幻觉”“真实用户规模”等无证据描述。

## 10. 建议的简历表述

### 当前版本可使用

- 独立构建 React + Spring Boot + FastAPI 的简历—岗位匹配应用，以 MySQL 作为事实源，通过 transactional outbox、RabbitMQ worker、有限重试与陈旧任务恢复完成异步 Agent 分析闭环。
- 实现 3 个白名单工具的有界 Tool Calling Agent，通过 Pydantic schema、调用顺序、step/tool/error/token/context budget 和逐条 evidence ID 校验约束模型行为。
- 构建幂等 multipart 提交、publisher confirm/return、`DEAD` 终态、attempt fence 与 Redis cache-aside，并以 JUnit/Testcontainers、pytest、Vitest/MSW、Playwright 验证关键路径。
- 构建具备运行时 API 校验、退避轮询、请求取消、安全 Markdown、失败重试和响应式适配的 React 工作台。

### 完成改造后使用结果模板

只有实际获得数字后再替换占位符：

- 在 `N` 个版本化中英 eval case、`M` 次重复运行中，实现 retrieval Recall@5 `X%`、正向 claim 引用覆盖率 `Y%`、unsupported claim rate `Z%`，并提交绑定 commit/模型/Prompt 版本的评测报告。
- 将 hashing baseline 升级为 hybrid retrieval，使 Recall@5 提升 `X` 个百分点，同时将 warm p95 控制在 `Y ms`、平均单次成本控制在 `Z`。
- 设计版本化结构化报告与 evidence provenance，打通 FastAPI、Spring Boot、MySQL、OpenAPI/Zod 和 React 证据交互，并以浏览器 E2E 验证 claim 到原文片段的完整链路。
- 通过 `N` 类故障注入验证 broker 断连、重复消息、Agent 超时与缓存下线下的最终状态和恢复时间，结果以 dashboard/测试 artifact 留存。

### 暂时不要写

- “使用生产级语义向量数据库”——当前没有。
- “消除幻觉”——当前只能约束引用结构，不能保证语义事实完全正确。
- “自动 fallback”——Agent/legacy 是配置切换，不是自动降级策略。
- “生产级高可用”“线上准确率”“日调用量”“降本比例”——当前没有真实证据。
- “完全自主 Agent”或“多 Agent”——当前准确表述是 bounded evidence-retrieval agent。

## 11. 最终取舍建议

如果时间只够做三件事，按以下顺序：

1. **真实 eval + hashing/semantic 对比结果**：直接补足 AI 含金量。
2. **结构化报告 + claim—evidence UI**：直接补足产品价值和全栈深度。
3. **无凭据 Demo + 截图/录屏 + 根 README**：让招聘方真正看到前两项。

公开部署安全是条件性 P0：不公开接收真实简历时，可以先用合成 Demo 控制范围；一旦公网接收真实数据，身份隔离、限流、删除/保留和网络收缩必须先于新增功能。
