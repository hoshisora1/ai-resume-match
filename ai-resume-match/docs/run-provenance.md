# Agent 运行溯源与输入指纹

## 1. 目标

`analysis-run-v1` 用于回答一次报告“由什么版本、在什么预算和链路下生成”。它把模型、Prompt、Retriever、Verifier、token/cost、W3C trace、去参数化工具轨迹与 `agent-run-v1` 运行元数据贯穿 Python、Java、MySQL、REST、Zod 和 React。

该机制提供的是**可核验运行证据**，不是确定性重放系统。它不会保存简历/JD 原文副本、工具参数、模型输出草稿或隐藏推理，也无法消除外部模型版本漂移和服务端非确定性。

## 2. `agent-run-v1` 契约

```json
{
  "schemaVersion": "agent-run-v1",
  "requestSchemaVersion": "agent-analysis-request-v1",
  "agentRuntimeVersion": "bounded-tool-agent-v1",
  "inputFingerprintVersion": "sha256-task-scoped-length-prefixed-v1",
  "inputFingerprint": "6d4c...b5d1",
  "chatProviderCalls": 3,
  "chatProviderDurationMs": 840,
  "toolDurationMs": 41,
  "totalDurationMs": 1120,
  "contextCharsSent": 9842
}
```

- `requestSchemaVersion` 固定请求字段及规范化语义。
- `agentRuntimeVersion` 标识有界工具循环实现，而非模型名称；模型、Prompt、Retriever、Verifier 各有独立版本字段。
- `chatProviderCalls` 必须等于 Agent `steps`。
- `chatProviderDurationMs` 只统计 chat provider 等待时间；embedding 等检索耗时包含在对应 tool trace 与 `toolDurationMs` 中。
- `toolDurationMs` 必须等于所有去参数化 tool trace 的耗时总和。
- `totalDurationMs` 是 Agent 内部端到端耗时，不等同于浏览器或 Java 观测到的网络端到端延迟。
- `contextCharsSent` 是累计序列化 provider 上下文字符数，是资源预算证据，不是 token 或账单估算。

## 3. 输入指纹算法

输入指纹作用于进入 Agent 的 PII 清理后、请求规范化后的内容。固定顺序为：

1. 指纹版本；
2. 十进制 `taskId`；
3. `resumeText`；
4. `jobTitle`；
5. `jobDescription`；
6. 十进制技能标签数量；
7. 每个技能标签，保留顺序。

每个 UTF-8 字段前写入 8 字节大端无符号长度，再计算 SHA-256：

```text
SHA-256(
  len(version) || version ||
  len(taskId) || taskId ||
  len(resumeText) || resumeText ||
  ...
)
```

长度前缀消除字段拼接歧义。`correlationId` 属于运维元数据，不参与指纹。同一任务、同一规范化输入的重试得到相同值；`taskId` 参与计算，因此相同文档作为不同任务提交时不会产生可跨任务复用的固定内容标识。

指纹不是加密或匿名化：掌握候选内容和任务 ID 的主体仍可离线验证猜测。因此它只存入受 owner 隔离、受保留/删除策略约束的报告 provenance，不写入日志、metric label 或 trace attribute。

## 4. 双边校验与信任边界

Python 使用 Pydantic 约束字段版本、范围、次数和时长关系。Java 收到响应后不直接信任 Agent：

- 使用同一长度前缀算法独立重算指纹；
- 校验四个契约版本和 64 位小写十六进制格式；
- 校验 chat 调用次数与 step 一致；
- 校验工具耗时与 tool trace 闭包一致；
- 校验耗时、上下文字符和 trace ID 上界/格式；
- 校验通过后才与结构化报告原子持久化。

前端 Zod 再执行一次格式和关系校验，避免损坏的历史/缓存 JSON 进入渲染层。Java 与 Python 使用同一硬编码测试向量，防止两种语言实现悄然漂移。

## 5. 滚动升级与评测

`runMetadata` 是 `analysis-run-v1` 的可空新增字段。旧 Agent 响应和历史报告没有该字段时，Java 与前端仍可读取并显示已有 provenance；新 Python 响应必须生成完整 `agent-run-v1`。

live eval 逐条保存 `runMetadata`，并聚合 chat provider、tool、Agent 总耗时和 context chars 的 p50/p95。评测数据集版本与 SHA-256 属于 eval artifact 顶层，而不是普通用户报告：生产报告没有“数据集版本”这一真实概念，不应为了字段齐全伪造一个值。

## 6. 能力边界

若需要真正重放，还需受控保留或重新提供原始输入、固定 provider/model snapshot、精确 Prompt/Retriever/Verifier 依赖、随机性参数、外部响应与环境镜像。当前实现只证明“报告绑定到了哪个任务范围内的规范化输入和运行契约”，不承诺相同指纹再次执行会得到相同自然语言输出。
