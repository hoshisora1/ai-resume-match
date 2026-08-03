# Agent evaluations

`cases.jsonl` contains synthetic, non-personal regression cases for grounding, tool order, prompt injection resistance, score calibration and latency bounds. Grounding passes only when every rendered positive claim has one or more citations and every cited ID resolves to exactly one rendered evidence excerpt mapping; a bare `resume:` string is not sufficient.

Start the Agent service, then run:

```powershell
$env:AGENT_SERVICE_TOKEN="dev-agent-token"
.\.venv\Scripts\python.exe evals\run_evals.py `
  --output eval-results\latest.json
```

The runner records only case IDs, checks, scores, latency, steps and token usage. It does not copy resume or JD bodies into the result file.
