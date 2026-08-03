# AI Resume Match Agent Service

Python/FastAPI service that turns the original single-prompt RAG pipeline into a bounded, grounded tool-calling agent.

The model can call only three allowlisted tools:

1. `get_job_requirements` reads the untrusted JD and normalized tags.
2. `search_resume_evidence` performs focused local retrieval and returns stable evidence IDs.
3. `submit_match_report` validates the final structured result and each positive claim's evidence references.

`coreClaims` and `matchedSkills` are structured as `{ claim, evidenceIds }`; positive claims without at least one ID from this run's retrieval results are rejected. The final Markdown repeats those claim-level citations and includes a sanitized `evidence ID -> relevance score -> resume excerpt` mapping for review. Retrieval applies an exact-term collision guard and a minimum relevance score, so an unrelated search returns an empty result instead of an arbitrary chunk. After an empty search, the Agent may still finish with no positive claims and a score of 30 or below.

The runtime enforces maximum steps/tool calls, validates every argument with Pydantic, rejects unknown evidence IDs, never accepts plain text as a final answer, and places only the task ID—not resume, JD, title or tags—in the initial model context.

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
$env:AGENT_MAX_COMPLETION_TOKENS="1200"
$env:AGENT_MAX_TOTAL_TOKENS="50000"
$env:AGENT_MAX_CONTEXT_CHARS="100000"
.\.venv\Scripts\uvicorn.exe agent_service.main:app --port 8000
```

`POST /v1/agent/analyze` is an internal endpoint and requires `X-Agent-Token`. Raw resume/JD/tool outputs are intentionally excluded from logs and response traces.

The service fails startup when the API key, endpoint, model or internal token is missing, or when runtime bounds are invalid. Each provider request includes `max_completion_tokens`; an analysis also stops when cumulative provider-reported total tokens or cumulative serialized context characters exceed their configured hard budget. The character count is a deterministic resource guard, not a token/cost estimate. If any provider response omits complete usage fields, `modelUsage.providerReported` is `false`; reported numbers may then be partial and are not an invoice. `/health` does not call the model provider; it reports process/config readiness rather than upstream availability.

## Evaluations

With the service running against a configured model:

```powershell
$env:AGENT_SERVICE_TOKEN="dev-agent-token"
.\.venv\Scripts\python.exe evals\run_evals.py `
  --output eval-results\latest.json
```

The five synthetic cases check tool order, structurally resolvable claim citations, prompt-injection resistance, score calibration, required/forbidden report terms, step budget, latency and token usage. The offline pytest suite additionally covers empty retrieval, forged evidence IDs inside resume text, uncited claims, conservative no-evidence completion and Markdown injection in evidence excerpts. The output intentionally excludes resume and JD bodies.
