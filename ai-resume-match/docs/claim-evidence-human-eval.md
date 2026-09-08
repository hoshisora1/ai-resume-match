# Claim–Evidence 双人盲标与仲裁流程

本文档定义如何评估“岗位要求是否真的被引用简历片段支持”。它补充结构化 evidence ID 校验，但不把引用 ID 合法性、规则 verifier 输出或模型自评分数当作人工 ground truth。

当前仓库只提供可执行流程和合成测试，没有提交真实双人标注结果。因此在两位独立标注者完成盲标、第三人完成分歧仲裁前，不能宣称 unsupported-positive-claim rate、语义 grounding precision 或人工一致性已经达标。

## 1. 角色与隔离

- 协调者：运行 live eval、生成盲标任务与 prediction key，保存数据集和 commit 信息。
- 标注者 A/B：只能看到 `tasks.json` 和各自的标签文件，不能看到 prediction key、对方标签、模型状态或 verifier 版本。
- 仲裁者 C：只处理 A/B 分歧；不能与 A/B 使用同一身份。
- 软件会验证三个 ID 不同、任务 hash 一致、每个 pair 恰好标注一次，以及仲裁项恰好等于分歧集合。不同 ID 不能证明现实中确实由不同人员完成，人员独立性仍由协调者负责。

`prediction-key.json` 必须由协调者单独保存，不能与 `tasks.json` 一起发给标注者。普通 live eval artifact 仍不保存证据原文；只有显式使用 `--annotation-source-output` 才会创建包含引用片段的独立文件。

## 2. 标签定义

每个 pair 只展示一个 JD requirement 和运行时最终接受的零到多个简历片段。标注时不得使用片段之外的知识。

| 标签 | 判定规则 |
| --- | --- |
| `supported` | 片段直接支持完整 requirement，主体、技能、范围和经验强度一致，且没有相关否定或冲突。 |
| `partial` | 片段只支持部分要求、较弱层级或相邻能力，例如“参加培训”不能完整支持“生产运维经验”。 |
| `unsupported` | 片段为空、只有关键词、属于不同上下文，或明确否定/冲突，无法支持该 requirement。 |

`confidence` 使用 1–3：1 表示高度含糊，2 表示基本可判，3 表示证据明确。`notes` 只用于标注和仲裁，不会进入最终指标 artifact。

## 3. 生成盲标任务

以下命令在 `agent-service` 目录执行。源数据集是仓库中的合成数据；若换成真实简历，必须先取得授权、完成脱敏并按敏感数据管理，不能提交 source、tasks、标签或仲裁文件。

首先运行一次真实 Agent eval。人工任务不允许 `--repeat > 1`，并且只有本次选择的所有 case 都通过结构/grounding 门禁时才会写出 annotation source：

```powershell
.\.venv\Scripts\python.exe evals\run_evals.py `
  --repeat 1 `
  --max-cases 120 `
  --output eval-results\claim-evidence\live-summary.json `
  --annotation-source-output eval-results\claim-evidence\source.json
```

为防止把旧数据误认成本次运行结果，`--annotation-source-output` 必须指向尚不存在的新文件；失败、case 缺失或导出顺序不一致时不会写文件。

`source.json` 只包含 case ID、tag、requirement、最终状态、verifier version 和被 requirement 引用的片段，不包含完整简历/JD、内部 token、工具参数、隐藏推理或 provider 错误正文。它仍包含简历片段，因此属于敏感评测输入。

然后拆分盲标任务和预测答案：

```powershell
.\.venv\Scripts\python.exe evals\prepare_claim_evidence_annotations.py `
  --source eval-results\claim-evidence\source.json `
  --tasks eval-results\claim-evidence\tasks.json `
  --prediction-key eval-results\claim-evidence\prediction-key.json `
  --label-template eval-results\claim-evidence\label-template.json
```

`tasks.json` 不含 case ID、模型预测、verifier version 或 evidence ID。`taskSetSha256` 绑定数据集、source hash、claim 和片段；任务被修改后，标签文件会被拒绝。`label-template.json` 故意使用 `TODO/0`，在全部填完前不符合 schema，不能意外生成看似有效的报告。

协调者分别复制模板为 `annotator-a.json` 和 `annotator-b.json`，替换匿名 ID，并独立填写全部 `label/confidence/notes`。

## 4. 生成并完成分歧仲裁

A/B 完成后生成只包含分歧 pair 的仲裁模板：

```powershell
.\.venv\Scripts\python.exe evals\prepare_claim_evidence_adjudication.py `
  --tasks eval-results\claim-evidence\tasks.json `
  --annotator-a eval-results\claim-evidence\annotator-a.json `
  --annotator-b eval-results\claim-evidence\annotator-b.json `
  --output eval-results\claim-evidence\adjudication-template.json
```

仲裁者复制该文件，替换独立 ID，并为每个分歧填写最终 `label` 和非空 `rationale`。没有分歧时可以不传 adjudication 文件。

## 5. 计算指标和质量门槛

```powershell
.\.venv\Scripts\python.exe evals\run_claim_evidence_eval.py `
  --tasks eval-results\claim-evidence\tasks.json `
  --prediction-key eval-results\claim-evidence\prediction-key.json `
  --annotator-a eval-results\claim-evidence\annotator-a.json `
  --annotator-b eval-results\claim-evidence\annotator-b.json `
  --adjudication eval-results\claim-evidence\adjudication.json `
  --min-pairs 100 `
  --min-accepted-predictions 100 `
  --min-rejected-predictions 20 `
  --min-kappa 0.80 `
  --max-unsupported-positive-rate 0.02 `
  --max-false-rejection-rate 0.10 `
  --output eval-results\claim-evidence\result.json
```

结果只保存 hash、版本集合、计数、标签分布、agreement、Cohen’s kappa、三分类 confusion matrix、错误率及其 Wilson 95% 区间，以及实际使用的门槛与每项门禁结果；不会复制 claim、证据、标注备注、人员 ID 或仲裁理由。CLI 默认使用上例门槛；若为了调试小样本而降低最小数量，生成的结果不能作为正式质量证据。

- `unsupportedPositiveClaimRate`：运行时接受为 `supported/partial` 的 requirement 中，人工最终判为 `unsupported` 的比例。
- `falseRejectionRate`：运行时判为 `not_found` 的 requirement 中，人工最终判为 `supported/partial` 的比例。
- accepted 或 rejected 分母为 0 时，对应 rate/区间为 `null` 且质量门禁失败，不会把“没有可评样本”显示为 0% 错误率。
- Cohen’s kappa 衡量仲裁前 A/B 一致性；阈值不应通过反复改标签“调到达标”，低一致性应先修订 rubric 并重新进行独立盲标。

首个可对外引用的结果还必须满足：至少 100 个 accepted prediction 和 20 个 rejected prediction；足够的中英文、否定、同义词、部分支持和无证据覆盖；绑定明确 commit、dataset SHA 和 task-set SHA；记录标注日期与人员角色；保留失败样例分类。合成数据结果只能描述该评测集，不能外推为招聘准确率或公平性结论。

## 6. 数据与发布边界

- `agent-service/eval-results/` 已被 `.gitignore` 排除，默认不进入版本库。
- prediction key 不发给标注者；A/B 标签在两人都提交前不能互相查看。
- 真实候选人数据不得进入公开 issue、CI artifact、截图或仓库；结束后按项目数据保留策略删除。
- 若要提交可公开的基线，只能使用确认无个人数据的合成集，并同时提交生成脚本、hash、指标定义和失败分类。
- 在真实双人流程完成前，[作品集优化方案](portfolio-improvement-plan.md) 中对应人工评测项保持未完成。
