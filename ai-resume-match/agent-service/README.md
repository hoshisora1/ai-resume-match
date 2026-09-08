# AI Resume Match Agent Service

Python/FastAPI service that turns the original single-prompt RAG pipeline into a bounded, grounded tool-calling agent.

The model can call only three allowlisted tools:

1. `get_job_requirements` parses JD clauses first, supplements them with uncovered skill tags, and selects at most five stable, weighted requirements with must-haves first. Modality belongs to its clause or explicit section heading; a preceding optional sentence cannot weaken a later mandatory requirement. The score describes the selected requirements, not exhaustive coverage of an arbitrarily long JD.
2. `search_resume_evidence` searches one `requirementId` at a time and binds returned evidence to that requirement.
3. `submit_match_report` requires exactly one `supported | partial | not_found` assessment per requirement and validates same-requirement evidence references.

Tool schemas declare every field as required for strict function calling, including `topK` and nested `evidenceIds` (use an explicit empty array for `not_found`). Argument models inherit the shared API configuration directly; missing fields are rejected rather than filled with defaults.

The v2 lexical verifier uses literal technology-name boundaries: JavaScript cannot support Java, NoSQL cannot support SQL, and storage cannot support RAG. Chinese-adjacent names and punctuation in C++, C#, and Node.js remain valid matches.

The model no longer submits a `matchScore`, `coreClaims`, or `matchedSkills`. It proposes requirement statuses, then `conservative-lexical-negation-v2` independently checks cited excerpts for requirement-term coverage and nearby English/Chinese negation. The verifier can only preserve or downgrade a proposal; an exception fails closed to `not_found`. The runtime calculates the score from final status factors (`supported=1`, `partial=0.5`, `not_found=0`) and requirement weights; a missing must-have caps the result at 69. It then derives cited positive claims and gaps, returns structured `requirementResults` with `modelStatus`, final `status`, verifier reason/coverage/version, and renders the requirement breakdown plus a sanitized evidence map. Evidence retrieved for requirement A cannot be reused for requirement B unless B's own search also returned it.

This rule verifier is a conservative runtime guard, not an accuracy benchmark or semantic entailment model. It catches explicit patterns such as “did not use Kubernetes” and “尚未接入向量数据库”, but synonym/paraphrase support still requires an independently evaluated semantic retriever/verifier. Retrieval defaults to the credential-free hashing/exact-term baseline. An explicit `hybrid` mode combines that branch with real provider embeddings through normalized reciprocal-rank fusion; its presence is not treated as proof of quality until a provider A/B artifact passes the same qrels gates.

The runtime enforces maximum steps/tool calls, validates every argument with Pydantic, rejects unknown evidence IDs, never accepts plain text as a final answer, and places only the task ID—not resume, JD, title or tags—in the initial model context.

Successful responses now include a versioned `structuredReport` (`match-report-v2`) alongside the derived Markdown compatibility view. The structured graph contains requirement decisions, grounded claims, only the evidence cited by the final decision, and a deterministic `scoreBreakdown`. Top-level score and requirement results must exactly match that graph. Versioned provenance (`model`, prompt/retriever/verifier versions, aggregate usage, parameter-free tool trace, optional W3C trace ID, and `agent-run-v1` request/runtime/fingerprint/timing metadata) is returned for Java persistence; it deliberately excludes raw resume/JD text, tool arguments and hidden model reasoning. The SHA-256 fingerprint is length-prefixed and task-scoped, so it supports same-task input verification without creating a reusable cross-task document identifier; it is not encryption or a replay snapshot.

## Local verification

```powershell
py -3.13 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\python.exe -m pytest
```

## Local service

```powershell
$env:AI_API_KEY="replace-with-local-key"
$env:AI_ENDPOINT="https://api.openai.com/v1/chat/completions"
$env:AI_MODEL="gpt-4o-mini"
$env:AGENT_SERVICE_TOKEN="dev-agent-token"
$env:AGENT_MODEL_TIMEOUT_SECONDS="45"
$env:AGENT_ANALYSIS_TIMEOUT_SECONDS="50"
$env:AGENT_MAX_COMPLETION_TOKENS="1200"
$env:AGENT_MAX_TOTAL_TOKENS="50000"
$env:AGENT_MAX_CONTEXT_CHARS="100000"
$env:AGENT_MAX_CONCURRENT_ANALYSES="4"
$env:AGENT_CAPACITY_ACQUIRE_TIMEOUT_SECONDS="0.1"
# Optional; all three values must come from one dated provider pricing source.
$env:AGENT_MODEL_PRICING_VERSION="provider-price-YYYY-MM-DD"
$env:AGENT_MODEL_INPUT_COST_USD_PER_MILLION_TOKENS="<provider-input-price>"
$env:AGENT_MODEL_OUTPUT_COST_USD_PER_MILLION_TOKENS="<provider-output-price>"
$env:AGENT_TRACING_ENABLED="false"
$env:OTEL_EXPORTER_OTLP_TRACES_ENDPOINT="http://localhost:4318/v1/traces"
$env:RETRIEVER_MODE="hashing"
.\.venv\Scripts\uvicorn.exe agent_service.main:app --port 8000
```

`POST /v1/agent/analyze` is an internal endpoint and requires `X-Agent-Token`. Raw resume/JD/tool outputs are intentionally excluded from logs and response traces. Optional `AGENT_TRACING_ENABLED=true` enables FastAPI and per-client HTTPX W3C spans and OTLP/HTTP export; incoming Java context is continued through chat and embedding provider calls, and a successful response returns its lowercase 32-hex `traceId`. `/health` reports tracing as enabled/disabled and the configured per-process analysis capacity. Instrumentation does not capture bodies or authentication headers; never place credentials or candidate data in endpoint query strings. `/metrics` exposes Prometheus text for whole-analysis/provider/tool latency and outcomes, in-progress work, capacity/rejections, reported token counts, retrieval hit/empty results, protocol denials, authentication failures, final requirement-status distribution, versioned USD cost estimates and estimation coverage. Labels are collapsed to fixed allowlists: task/correlation IDs, queries, documents, exceptions, model output, tool arguments, exact model/retriever names, prices and pricing versions are never labels. Keep the unauthenticated health/metrics surfaces and OTLP collector on an internal network in shared deployments.

The service fails startup when the chat API key, endpoint, model or internal token is missing, or when runtime bounds are invalid. Hybrid mode additionally requires a non-blank embedding endpoint/key/model and an embedding timeout below the whole-analysis deadline. `AGENT_ANALYSIS_TIMEOUT_SECONDS` must be greater than `AGENT_MODEL_TIMEOUT_SECONDS`; the default pair is 50/45 seconds, while Java waits 60 seconds. `AGENT_MAX_CONCURRENT_ANALYSES` bounds authorized in-flight analyses per process; a request that cannot acquire capacity within `AGENT_CAPACITY_ACQUIRE_TIMEOUT_SECONDS` receives `503 AGENT_CAPACITY_EXHAUSTED`, `retryable=true`, and `retryAfterSeconds=1` before any chat or embedding provider call. Each chat request includes `max_completion_tokens`; an analysis also stops when cumulative provider-reported total tokens or cumulative serialized context characters exceed their configured hard budget. The character count is a deterministic resource guard, not a token/cost estimate. If any provider response omits complete usage fields, `modelUsage.providerReported` is `false`; the corresponding token counter is labeled `provider_reported="false"`, and its value may be partial rather than an invoice. Cost estimation is disabled unless pricing version, input price and output price are all supplied; prices must be finite non-negative USD per million tokens. Complete usage then yields an eight-decimal `estimatedCostUsd` and `pricingVersion` in the response. The estimate covers only chat prompt/completion tokens under the configured flat rates; it excludes embedding calls, cached-token tiers, storage, network, tax, and other provider charges. Prometheus cost totals are floating-point operational signals, while the response Decimal string is authoritative for the per-analysis estimate. `/health` reports `costEstimation=enabled|disabled` without exposing prices, and does not call either provider.

Analysis failures use a stable internal contract: `{code, message, retryable, retryAfterSeconds}`. Protocol and malformed model-output failures are final (`422`); local capacity exhaustion, provider network errors, `408`, `429`, `5xx`, and the global deadline are retryable. Provider response bodies are not forwarded.

## Evaluations

Run the deterministic retrieval evaluation without model credentials:

```powershell
.\.venv\Scripts\python.exe evals\run_retrieval_eval.py --summary-only
```

The versioned retrieval dataset contains 60 synthetic queries with stable source spans. The committed hashing baseline records Recall@5 `0.8864`, MRR@5 `0.8636`, and a `0.0000` false-positive rate on 16 no-evidence cases. The five misses are synonym cases, so this result documents the lexical baseline's limit rather than claiming semantic retrieval quality.

To run a real embedding A/B comparison, configure `EMBEDDING_ENDPOINT`, `EMBEDDING_API_KEY` (or `AI_API_KEY`) and an exact `EMBEDDING_MODEL`, then run:

```powershell
.\.venv\Scripts\python.exe evals\run_retrieval_eval.py `
  --retriever hybrid `
  --baseline evals\baselines\hashing-v1.json `
  --min-recall 0.90 `
  --min-mrr 0.80 `
  --max-false-positive-rate 0.05 `
  --min-recall-lift 0 `
  --max-false-positive-rate-increase 0.05 `
  --output eval-results\hybrid.json `
  --markdown eval-results\hybrid.md
```

The hybrid retriever batches provider calls, caches document and repeated-query vectors for one analysis, validates provider dimensions and finite values, and records the embedding model in its version metadata. Provider I/O has its own timeout and runs outside the event loop. There is no automatic fallback to hashing: deployment selects one explicit mode so provider failure cannot silently change retrieval semantics.

With the service running against a configured model:

```powershell
$env:AGENT_SERVICE_TOKEN="dev-agent-token"
.\.venv\Scripts\python.exe evals\run_evals.py `
  --repeat 3 `
  --summary-only `
  --output eval-results\latest.json
```

The `agent-live-eval-v4` live-model dataset contains 120 synthetic cases. It retains ten-case strong, partial, no-evidence, and negation/boundary core slices; expands résumé and JD injection to 25 cases each; and adds five-case long-document, format-noise, temporal-boundary, acronym/synonym, forged-evidence, and protocol-adversarial slices. Sixty cases carry the security/adversarial tags. Use repeat runs to measure score stability, or `--tag resume-injection` to run one slice. The evaluator recomputes the score from `requirementResults` in addition to checking grounding. The `agent-live-eval-result-v3` artifact binds the dataset hash to commit/model/prompt/retriever versions and records per-tag pass rate, p50/p95 latency, token usage, score standard deviation, cost-estimated run coverage, aggregate estimated USD, every pricing version observed, and an optional per-run trace ID for Grafana/Tempo lookup. No-evidence cases require both a score at or below 30 and an empty positive-claim set. Evaluation artifacts intentionally exclude resume and JD bodies; the case count is test coverage, not a pass-rate claim, and no provider result is claimed until this command is actually run against an explicitly configured model.
