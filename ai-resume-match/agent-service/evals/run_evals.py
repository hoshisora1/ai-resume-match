from __future__ import annotations

import argparse
import json
import os
import statistics
import time
from pathlib import Path
from typing import Any

import httpx

from agent_service.evaluation import evaluate_response


def load_cases(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def main() -> int:
    parser = argparse.ArgumentParser(description="Run grounded Agent evaluation cases")
    parser.add_argument(
        "--endpoint",
        default=os.getenv("AGENT_EVAL_ENDPOINT", "http://127.0.0.1:8000/v1/agent/analyze"),
    )
    parser.add_argument("--token", default=os.getenv("AGENT_SERVICE_TOKEN", ""))
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("cases.jsonl"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--min-pass-rate", type=float, default=1.0)
    args = parser.parse_args()
    if not args.token:
        parser.error("--token or AGENT_SERVICE_TOKEN is required")

    results: list[dict[str, Any]] = []
    latencies: list[int] = []
    with httpx.Client(timeout=100) as client:
        for case in load_cases(args.cases):
            started = time.perf_counter()
            response = client.post(
                args.endpoint,
                headers={"X-Agent-Token": args.token},
                json=case["request"],
            )
            latency_ms = round((time.perf_counter() - started) * 1000)
            latencies.append(latency_ms)
            if response.is_success:
                payload = response.json()
                checks = evaluate_response(payload, case["expectations"], latency_ms)
            else:
                payload = {}
                checks = {"httpSuccess": False}
            results.append(
                {
                    "id": case["id"],
                    "passed": response.is_success and all(checks.values()),
                    "status": response.status_code,
                    "latencyMs": latency_ms,
                    "matchScore": payload.get("matchScore"),
                    "steps": payload.get("steps"),
                    "modelUsage": payload.get("modelUsage"),
                    "checks": checks,
                }
            )

    passed = sum(1 for result in results if result["passed"])
    pass_rate = passed / len(results) if results else 0.0
    summary = {
        "cases": len(results),
        "passed": passed,
        "passRate": round(pass_rate, 4),
        "medianLatencyMs": round(statistics.median(latencies)) if latencies else None,
        "maxLatencyMs": max(latencies) if latencies else None,
        "results": results,
    }
    rendered = json.dumps(summary, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    return 0 if pass_rate >= args.min_pass_rate else 1


if __name__ == "__main__":
    raise SystemExit(main())
