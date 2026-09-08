from __future__ import annotations

import argparse
import json
import math
import os
import statistics
import subprocess
import time
from collections import defaultdict
from collections.abc import Callable
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any

import httpx

from agent_service.claim_evidence_evaluation import (
    AnnotationSource,
    SourceCase,
    extract_source_case,
)
from agent_service.evaluation import AgentEvalCase, evaluate_response, load_agent_eval_cases


def git_metadata() -> tuple[str, bool]:
    repository = Path(__file__).resolve().parents[2]
    revision = os.getenv("GITHUB_SHA", "").strip()
    if not revision:
        completed = subprocess.run(
            ["git", "-C", str(repository), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        )
        revision = completed.stdout.strip()
    status = subprocess.run(
        ["git", "-C", str(repository), "status", "--porcelain", "--untracked-files=all"],
        check=True,
        capture_output=True,
        text=True,
    )
    return revision, bool(status.stdout.strip())


def nearest_rank_percentile(values: list[int], percentile: float) -> int | None:
    if not values:
        return None
    if not 0 < percentile <= 1:
        raise ValueError("percentile must be in (0, 1]")
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * percentile) - 1)]


def select_cases(
    cases: list[AgentEvalCase],
    *,
    tags: list[str],
    max_cases: int | None,
) -> list[AgentEvalCase]:
    requested = {tag.strip().casefold() for tag in tags if tag.strip()}
    selected = [
        case for case in cases if not requested or requested.intersection(case.tags)
    ]
    if max_cases is not None:
        if max_cases < 1:
            raise ValueError("max_cases must be positive")
        selected = selected[:max_cases]
    if not selected:
        raise ValueError("no Agent eval cases matched the requested filters")
    return selected


def _tag_metrics(results: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for result in results:
        for tag in result["tags"]:
            grouped[tag].append(result)
    return {
        tag: {
            "runs": len(items),
            "passed": sum(1 for item in items if item["passed"]),
            "passRate": round(
                sum(1 for item in items if item["passed"]) / len(items),
                4,
            ),
        }
        for tag, items in sorted(grouped.items())
    }


def summarize_results(
    *,
    dataset_version: str,
    dataset_sha256: str,
    selected_cases: list[AgentEvalCase],
    results: list[dict[str, Any]],
    repeat: int,
    requested_tags: list[str],
    run_metadata: dict[str, Any],
) -> dict[str, Any]:
    passed_runs = sum(1 for result in results if result["passed"])
    latencies = [int(result["latencyMs"]) for result in results]
    by_case: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for result in results:
        by_case[result["id"]].append(result)

    stability: list[dict[str, Any]] = []
    for case in selected_cases:
        scores = [
            int(result["matchScore"])
            for result in by_case[case.case_id]
            if isinstance(result.get("matchScore"), int)
        ]
        stability.append(
            {
                "id": case.case_id,
                "samples": len(scores),
                "scores": scores,
                "mean": round(statistics.mean(scores), 4) if scores else None,
                "standardDeviation": (
                    round(statistics.pstdev(scores), 4) if len(scores) >= 2 else None
                ),
            }
        )

    measured_deviations = [
        item["standardDeviation"]
        for item in stability
        if item["standardDeviation"] is not None
    ]
    case_passes = sum(
        1
        for case in selected_cases
        if len(by_case[case.case_id]) == repeat
        and all(result["passed"] for result in by_case[case.case_id])
    )
    usage_rows = [
        result["modelUsage"]
        for result in results
        if isinstance(result.get("modelUsage"), dict)
    ]
    cost_rows: list[Decimal] = []
    pricing_versions: set[str] = set()
    for usage in usage_rows:
        raw_cost = usage.get("estimatedCostUsd")
        pricing_version = usage.get("pricingVersion")
        if raw_cost is None or not isinstance(pricing_version, str) or not pricing_version:
            continue
        try:
            cost = Decimal(str(raw_cost))
        except InvalidOperation:
            continue
        if not cost.is_finite() or cost < 0:
            continue
        cost_rows.append(cost)
        pricing_versions.add(pricing_version)

    agent_run_rows = [
        result["runMetadata"]
        for result in results
        if isinstance(result.get("runMetadata"), dict)
    ]

    def run_counts(field: str) -> list[int]:
        return [
            value
            for row in agent_run_rows
            if isinstance((value := row.get(field)), int) and value >= 0
        ]

    chat_durations = run_counts("chatProviderDurationMs")
    tool_durations = run_counts("toolDurationMs")
    total_durations = run_counts("totalDurationMs")
    context_sizes = run_counts("contextCharsSent")
    chat_calls = run_counts("chatProviderCalls")

    return {
        "schemaVersion": "agent-live-eval-result-v3",
        "datasetVersion": dataset_version,
        "datasetSha256": dataset_sha256,
        "selection": {
            "tags": sorted({tag.casefold() for tag in requested_tags}),
            "cases": len(selected_cases),
            "repeat": repeat,
            "runs": len(results),
        },
        "metrics": {
            "passedRuns": passed_runs,
            "runPassRate": round(passed_runs / len(results), 4) if results else 0.0,
            "passedCases": case_passes,
            "casePassRate": round(case_passes / len(selected_cases), 4),
            "latencyP50Ms": nearest_rank_percentile(latencies, 0.50),
            "latencyP95Ms": nearest_rank_percentile(latencies, 0.95),
            "maxLatencyMs": max(latencies) if latencies else None,
            "maxScoreStandardDeviation": (
                max(measured_deviations) if measured_deviations else None
            ),
            "byTag": _tag_metrics(results),
        },
        "versions": {
            "models": sorted(
                {result["model"] for result in results if result.get("model")}
            ),
            "prompts": sorted(
                {
                    result["promptVersion"]
                    for result in results
                    if result.get("promptVersion")
                }
            ),
            "retrievers": sorted(
                {
                    result["retrieverVersion"]
                    for result in results
                    if result.get("retrieverVersion")
                }
            ),
            "verifiers": sorted(
                {
                    result["verifierVersion"]
                    for result in results
                    if result.get("verifierVersion")
                }
            ),
        },
        "modelUsage": {
            "reportedRuns": sum(
                1 for usage in usage_rows if usage.get("providerReported") is True
            ),
            "promptTokens": sum(int(usage.get("promptTokens", 0)) for usage in usage_rows),
            "completionTokens": sum(
                int(usage.get("completionTokens", 0)) for usage in usage_rows
            ),
            "totalTokens": sum(int(usage.get("totalTokens", 0)) for usage in usage_rows),
            "costEstimatedRuns": len(cost_rows),
            "estimatedCostUsd": format(sum(cost_rows, Decimal(0)), "f") if cost_rows else None,
            "pricingVersions": sorted(pricing_versions),
        },
        "agentRun": {
            "reportedRuns": len(agent_run_rows),
            "runtimeVersions": sorted(
                {
                    row["agentRuntimeVersion"]
                    for row in agent_run_rows
                    if isinstance(row.get("agentRuntimeVersion"), str)
                    and row["agentRuntimeVersion"]
                }
            ),
            "chatProviderCalls": sum(chat_calls),
            "chatProviderDurationP50Ms": nearest_rank_percentile(
                chat_durations, 0.50
            ),
            "chatProviderDurationP95Ms": nearest_rank_percentile(
                chat_durations, 0.95
            ),
            "toolDurationP95Ms": nearest_rank_percentile(tool_durations, 0.95),
            "totalDurationP50Ms": nearest_rank_percentile(total_durations, 0.50),
            "totalDurationP95Ms": nearest_rank_percentile(total_durations, 0.95),
            "contextCharsP95": nearest_rank_percentile(context_sizes, 0.95),
        },
        "scoreStability": stability,
        "run": run_metadata,
        "results": results,
    }


def execute_case(
    client: httpx.Client,
    *,
    endpoint: str,
    token: str,
    case: AgentEvalCase,
    run_index: int,
    annotation_sink: Callable[[SourceCase], None] | None = None,
) -> dict[str, Any]:
    started = time.perf_counter()
    try:
        response = client.post(
            endpoint,
            headers={"X-Agent-Token": token},
            json=case.request.model_dump(by_alias=True, exclude_none=True),
        )
        latency_ms = round((time.perf_counter() - started) * 1_000)
        payload: dict[str, Any] = {}
        if response.is_success:
            try:
                decoded = response.json()
                if isinstance(decoded, dict):
                    payload = decoded
                    checks = evaluate_response(
                        payload,
                        case.expectations.model_dump(by_alias=True),
                        latency_ms,
                    )
                    if annotation_sink is not None and all(checks.values()):
                        annotation_sink(
                            extract_source_case(
                                case_id=case.case_id,
                                tags=case.tags,
                                response=payload,
                            )
                        )
                else:
                    checks = {"validResponseJson": False}
            except ValueError:
                checks = {"validResponseJson": False}
        else:
            checks = {"httpSuccess": False}
            try:
                error_payload = response.json()
            except ValueError:
                error_payload = {}
        result = {
            "id": case.case_id,
            "tags": case.tags,
            "runIndex": run_index,
            "passed": response.is_success and all(checks.values()),
            "status": response.status_code,
            "latencyMs": latency_ms,
            "matchScore": payload.get("matchScore"),
            "steps": payload.get("steps"),
            "model": payload.get("model"),
            "promptVersion": payload.get("promptVersion"),
            "retrieverVersion": payload.get("retrieverVersion"),
            "verifierVersion": payload.get("verifierVersion"),
            "traceId": payload.get("traceId"),
            "runMetadata": payload.get("runMetadata"),
            "modelUsage": payload.get("modelUsage"),
            "checks": checks,
        }
        if not response.is_success:
            result["errorCode"] = (
                error_payload.get("code") if isinstance(error_payload, dict) else None
            )
        return result
    except httpx.RequestError as exc:
        return {
            "id": case.case_id,
            "tags": case.tags,
            "runIndex": run_index,
            "passed": False,
            "status": None,
            "latencyMs": round((time.perf_counter() - started) * 1_000),
            "matchScore": None,
            "steps": None,
            "model": None,
            "promptVersion": None,
            "retrieverVersion": None,
            "verifierVersion": None,
            "traceId": None,
            "runMetadata": None,
            "modelUsage": None,
            "checks": {"httpSuccess": False},
            "transportError": type(exc).__name__,
        }


def write_annotation_source_if_complete(
    *,
    output: Path,
    dataset_version: str,
    dataset_sha256: str,
    selected_cases: list[AgentEvalCase],
    results: list[dict[str, Any]],
    annotation_cases: list[SourceCase],
) -> bool:
    """Write sensitive excerpts only for a complete, passing, ordered eval selection."""

    selected_ids = [case.case_id for case in selected_cases]
    exported_ids = [case.case_id for case in annotation_cases]
    if exported_ids != selected_ids or len(results) != len(selected_cases):
        return False
    if not all(result.get("passed") is True for result in results):
        return False
    source = AnnotationSource(
        dataset_version=dataset_version,
        dataset_sha256=dataset_sha256,
        cases=annotation_cases,
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8", newline="\n") as handle:
        handle.write(
            json.dumps(source.model_dump(by_alias=True), ensure_ascii=False, indent=2)
            + "\n"
        )
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="Run grounded live Agent evaluation cases")
    parser.add_argument(
        "--endpoint",
        default=os.getenv("AGENT_EVAL_ENDPOINT", "http://127.0.0.1:8000/v1/agent/analyze"),
    )
    parser.add_argument("--token", default=os.getenv("AGENT_SERVICE_TOKEN", ""))
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("cases.jsonl"))
    parser.add_argument("--output", type=Path)
    parser.add_argument(
        "--annotation-source-output",
        type=Path,
        help=(
            "Explicitly export requirement/evidence excerpts for blinded human annotation; "
            "use only with approved synthetic or privacy-reviewed data"
        ),
    )
    parser.add_argument("--tag", action="append", default=[])
    parser.add_argument("--max-cases", type=int)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--timeout-seconds", type=float, default=65.0)
    parser.add_argument("--min-pass-rate", type=float, default=1.0)
    parser.add_argument("--max-score-stddev", type=float, default=3.0)
    parser.add_argument("--summary-only", action="store_true")
    args = parser.parse_args()
    if not args.token:
        parser.error("--token or AGENT_SERVICE_TOKEN is required")
    if args.repeat < 1:
        parser.error("--repeat must be positive")
    if args.annotation_source_output is not None and args.repeat != 1:
        parser.error("--annotation-source-output requires --repeat 1")
    if (
        args.annotation_source_output is not None
        and args.annotation_source_output.exists()
    ):
        parser.error("--annotation-source-output must not already exist")
    if not 0 <= args.min_pass_rate <= 1:
        parser.error("--min-pass-rate must be between 0 and 1")
    if args.max_score_stddev < 0:
        parser.error("--max-score-stddev must not be negative")

    dataset_version, cases, digest = load_agent_eval_cases(args.cases)
    try:
        selected = select_cases(cases, tags=args.tag, max_cases=args.max_cases)
    except ValueError as exc:
        parser.error(str(exc))

    results: list[dict[str, Any]] = []
    annotation_cases: list[SourceCase] = []
    with httpx.Client(timeout=args.timeout_seconds) as client:
        for case in selected:
            for run_index in range(1, args.repeat + 1):
                results.append(
                    execute_case(
                        client,
                        endpoint=args.endpoint,
                        token=args.token,
                        case=case,
                        run_index=run_index,
                        annotation_sink=(
                            annotation_cases.append
                            if args.annotation_source_output is not None
                            else None
                        ),
                    )
                )

    annotation_export_ok = True
    if args.annotation_source_output is not None:
        annotation_export_ok = write_annotation_source_if_complete(
            output=args.annotation_source_output,
            dataset_version=dataset_version,
            dataset_sha256=digest,
            selected_cases=selected,
            results=results,
            annotation_cases=annotation_cases,
        )

    revision, dirty = git_metadata()
    summary = summarize_results(
        dataset_version=dataset_version,
        dataset_sha256=digest,
        selected_cases=selected,
        results=results,
        repeat=args.repeat,
        requested_tags=args.tag,
        run_metadata={
            "commit": revision,
            "workingTreeDirty": dirty,
            "generatedAt": datetime.now(UTC).isoformat(),
            "runtime": "python",
            "temperature": 0,
        },
    )
    rendered = json.dumps(summary, ensure_ascii=False, indent=2) + "\n"
    console_payload = (
        {
            "schemaVersion": summary["schemaVersion"],
            "datasetVersion": dataset_version,
            "datasetSha256": digest,
            "selection": summary["selection"],
            "metrics": summary["metrics"],
            "versions": summary["versions"],
            "modelUsage": summary["modelUsage"],
            "agentRun": summary["agentRun"],
            "run": summary["run"],
        }
        if args.summary_only
        else summary
    )
    print(json.dumps(console_payload, ensure_ascii=False, indent=2))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8", newline="\n")

    metrics = summary["metrics"]
    pass_rate_ok = metrics["runPassRate"] >= args.min_pass_rate
    stability_ok = (
        args.repeat == 1
        or metrics["maxScoreStandardDeviation"] is None
        or metrics["maxScoreStandardDeviation"] <= args.max_score_stddev
    )
    return 0 if pass_rate_ok and stability_ok and annotation_export_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
