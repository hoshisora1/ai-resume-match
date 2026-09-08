# Agent evaluations

This directory contains two separate evaluation layers. Neither dataset contains personal data.

## Deterministic retrieval baseline

`retrieval_dataset_v1.json` contains 12 synthetic documents and 60 queries: 44 queries with relevant evidence and 16 explicit no-evidence queries. It covers English, Chinese, mixed-language, synonym, negation, long-text, multi-span, and no-evidence cases.

Each document has a stable `documentId`. Gold evidence uses `spanId/start/end` over normalized source text; cases use `relevantSpanIds` and `noRelevantEvidence`, which must be mutually exclusive. Retrieved chunks are matched to gold spans by source-offset overlap rather than unstable `resume:N` chunk IDs.

Run the offline evaluator without a model credential:

```powershell
.\.venv\Scripts\python.exe evals\run_retrieval_eval.py `
  --min-recall 0.88 `
  --min-mrr 0.86 `
  --max-false-positive-rate 0 `
  --summary-only
```

The committed hashing baseline is in `baselines/hashing-v1.json` with a readable summary in `baselines/hashing-v1.md`. Its current synthetic-dataset result is Recall@5 `0.8864`, MRR@5 `0.8636`, and false-positive rate `0.0000`. Five synonym cases are missed. This is a retriever regression baseline, not a hiring-quality or production-accuracy claim.

The same runner supports a credentialed hybrid A/B run with `--retriever hybrid --baseline baselines/hashing-v1.json`. It requires explicit `EMBEDDING_ENDPOINT`, `EMBEDDING_MODEL`, and `EMBEDDING_API_KEY` (or `AI_API_KEY`), verifies that baseline and candidate use the identical dataset hash and Top-K, and reports Recall/MRR lift plus false-positive-rate increase. No hybrid result is committed until that real provider run is reviewed; deterministic fake embeddings are used only for implementation tests, never as quality evidence.

The metric definitions are:

- Recall@5: macro average of the fraction of gold spans overlapped by the Top-5 chunks, over positive cases.
- MRR@5: macro average reciprocal rank of the first chunk overlapping any gold span, over positive cases.
- False-positive rate: fraction of `noRelevantEvidence=true` cases returning any chunk.

`build_retrieval_dataset.py` is the canonical synthetic fixture builder. It deterministically regenerates the JSON offsets, and tests assert the committed dataset has not drifted from that source.

## Live Agent evaluation

`cases.jsonl` contains 120 synthetic cases under dataset version `agent-live-eval-v4`. The original core keeps ten strong-match, ten partial-match, ten no-evidence, and ten negation/boundary cases. Résumé injection and JD injection now contain 25 cases each. Six dedicated slices add five cases each for long documents, format noise, temporal boundaries, acronyms/synonyms, forged evidence, and protocol-shaped adversarial input. In total, 60 cases carry both `security` and `adversarial` tags. `build_agent_dataset.py` is the canonical generator; tests validate the committed JSONL, unique IDs, score constraints, exact slice counts, attack-marker assertions, and canonical SHA-256.

Grounding passes only when every rendered positive claim has one or more citations and every cited ID resolves to exactly one rendered evidence excerpt mapping; a bare `resume:` string is not sufficient. The evaluator also reconstructs the weighted score from `requirementResults` and rejects a mismatch. The ten no-evidence cases additionally require no positive claims and a score no greater than 30. Required/forbidden term checks are regression smoke checks over Agent-authored sections, not semantic entailment metrics.

Start the Agent service, then run:

```powershell
$env:AGENT_SERVICE_TOKEN="dev-agent-token"
.\.venv\Scripts\python.exe evals\run_evals.py `
  --repeat 3 `
  --min-pass-rate 0.95 `
  --max-score-stddev 3 `
  --summary-only `
  --output eval-results\latest.json
```

For a focused security run, use `--tag security`; use `--tag resume-injection --tag jd-injection` for the 50 direct untrusted-text injection cases. Tags use OR selection. The repeat option reuses the same input so the report can calculate per-case score standard deviation. The runner also records dataset hash, commit/dirty state, model, prompt version, retriever version, verifier version, temperature, per-tag pass rates, endpoint p50/p95 latency, steps, aggregate token usage and safe `agent-run-v1` metadata. The latter adds Chat provider/Tool/Agent duration percentiles, Chat call totals and context-character p95; embedding wait remains part of the retrieval tool duration rather than the Chat provider field.

The result file contains case IDs and derived measurements only; it does not copy resume/JD bodies, provider error messages, internal tokens, tool arguments, or evidence excerpts. Each result may contain a task-scoped SHA-256 input fingerprint for same-task consistency checks; the fingerprint is not encryption or a replay snapshot and must not be promoted to logs, metrics, or traces. It requires an explicitly configured live provider and is not part of credential-free pull-request CI. This repository does not include a fabricated live result: generate and review the artifact against the target commit before quoting pass rate, stability, latency, or token numbers.

## Human claim-evidence evaluation

`run_evals.py --annotation-source-output <path>` is an explicit, repeat-1-only escape hatch for a privacy-reviewed human evaluation. It writes requirement results and only their referenced evidence excerpts to a separate source file, and refuses to write a partial source when any selected run fails. The normal live result remains excerpt-free.

`prepare_claim_evidence_annotations.py` separates that source into a blinded task set and a coordinator-only prediction key. `prepare_claim_evidence_adjudication.py` creates an intentionally incomplete template containing exactly the disagreements between two complete annotation files. `run_claim_evidence_eval.py` requires matching task hashes, distinct annotator/adjudicator IDs, complete pair coverage and exact disagreement adjudication before calculating agreement, Cohen's kappa, a three-class confusion matrix, unsupported-positive-claim rate, false-rejection rate and Wilson 95% intervals. Its artifact records the configured sample-size and quality gates; zero denominators are `null` and fail the gate instead of appearing as zero error. The derived result excludes claims, excerpts, notes, rationales and personnel IDs.

See [`../../docs/claim-evidence-human-eval.md`](../../docs/claim-evidence-human-eval.md) for the Chinese rubric, role separation, commands, thresholds, privacy boundary and publication rules. The tooling and synthetic workflow tests do not constitute a completed human evaluation.
