from __future__ import annotations

from pathlib import Path

import httpx
import pytest

from agent_service.claim_evidence_evaluation import AnnotationSource, SourceCase
from agent_service.evaluation import load_agent_eval_cases
from evals.run_evals import (
    execute_case,
    nearest_rank_percentile,
    select_cases,
    summarize_results,
    write_annotation_source_if_complete,
)


def _result(case_id: str, tags: list[str], run_index: int, score: int) -> dict:
    return {
        "id": case_id,
        "tags": tags,
        "runIndex": run_index,
        "passed": True,
        "status": 200,
        "latencyMs": 1_000 + run_index,
        "matchScore": score,
        "steps": 3,
        "model": "model-2026-01",
        "promptVersion": "prompt-v1",
        "retrieverVersion": "retriever-v1",
        "verifierVersion": "verifier-v1",
        "traceId": "0123456789abcdef0123456789abcdef",
        "runMetadata": {
            "schemaVersion": "agent-run-v1",
            "requestSchemaVersion": "agent-analysis-request-v1",
            "agentRuntimeVersion": "bounded-tool-agent-v1",
            "inputFingerprintVersion": "sha256-task-scoped-length-prefixed-v1",
            "inputFingerprint": "a" * 64,
            "chatProviderCalls": 3,
            "chatProviderDurationMs": 200 + run_index,
            "toolDurationMs": 30,
            "totalDurationMs": 300 + run_index,
            "contextCharsSent": 2_000 + run_index,
        },
        "modelUsage": {
            "promptTokens": 100,
            "completionTokens": 20,
            "totalTokens": 120,
            "providerReported": True,
            "estimatedCostUsd": "0.00027000",
            "pricingVersion": "provider-price-2026-08-01",
        },
        "checks": {"groundedEvidence": True},
    }


def test_case_selection_filters_balanced_slice() -> None:
    _, cases, _ = load_agent_eval_cases(
        Path(__file__).parents[1] / "evals" / "cases.jsonl"
    )

    selected = select_cases(cases, tags=["resume-injection"], max_cases=None)

    assert len(selected) == 25
    assert all("resume-injection" in case.tags for case in selected)


def test_summary_records_versions_tokens_tag_metrics_and_score_stability() -> None:
    _, cases, _ = load_agent_eval_cases(
        Path(__file__).parents[1] / "evals" / "cases.jsonl"
    )
    selected = cases[:2]
    results = [
        _result(selected[0].case_id, selected[0].tags, 1, 70),
        _result(selected[0].case_id, selected[0].tags, 2, 80),
        _result(selected[1].case_id, selected[1].tags, 1, 60),
        _result(selected[1].case_id, selected[1].tags, 2, 60),
    ]

    summary = summarize_results(
        dataset_version="dataset-v1",
        dataset_sha256="abc",
        selected_cases=selected,
        results=results,
        repeat=2,
        requested_tags=["strong"],
        run_metadata={"commit": "deadbeef"},
    )

    assert summary["metrics"]["runPassRate"] == 1.0
    assert summary["metrics"]["casePassRate"] == 1.0
    assert summary["metrics"]["maxScoreStandardDeviation"] == 5.0
    assert summary["metrics"]["byTag"]["strong"]["runs"] == 4
    assert summary["versions"] == {
        "models": ["model-2026-01"],
        "prompts": ["prompt-v1"],
        "retrievers": ["retriever-v1"],
        "verifiers": ["verifier-v1"],
    }
    assert summary["modelUsage"]["totalTokens"] == 480
    assert summary["modelUsage"]["reportedRuns"] == 4
    assert summary["modelUsage"]["costEstimatedRuns"] == 4
    assert summary["modelUsage"]["estimatedCostUsd"] == "0.00108000"
    assert summary["modelUsage"]["pricingVersions"] == ["provider-price-2026-08-01"]
    assert summary["agentRun"] == {
        "reportedRuns": 4,
        "runtimeVersions": ["bounded-tool-agent-v1"],
        "chatProviderCalls": 12,
        "chatProviderDurationP50Ms": 201,
        "chatProviderDurationP95Ms": 202,
        "toolDurationP95Ms": 30,
        "totalDurationP50Ms": 301,
        "totalDurationP95Ms": 302,
        "contextCharsP95": 2002,
    }
    assert summary["schemaVersion"] == "agent-live-eval-result-v3"
    assert all(
        result["traceId"] == "0123456789abcdef0123456789abcdef"
        for result in summary["results"]
    )
    assert all(result["runMetadata"] is not None for result in summary["results"])


def test_nearest_rank_percentile_is_deterministic() -> None:
    assert nearest_rank_percentile([50, 10, 20, 40, 30], 0.5) == 30
    assert nearest_rank_percentile([50, 10, 20, 40, 30], 0.95) == 50


def test_http_error_result_does_not_copy_provider_or_document_body() -> None:
    _, cases, _ = load_agent_eval_cases(
        Path(__file__).parents[1] / "evals" / "cases.jsonl"
    )
    case = cases[0]
    transport = httpx.MockTransport(
        lambda _: httpx.Response(
            503,
            json={
                "code": "MODEL_PROVIDER_UNAVAILABLE",
                "message": "provider-secret and résumé body must not be copied",
            },
        )
    )

    with httpx.Client(transport=transport) as client:
        result = execute_case(
            client,
            endpoint="https://agent.test/analyze",
            token="secret-token",
            case=case,
            run_index=1,
        )

    rendered = str(result)
    assert result["errorCode"] == "MODEL_PROVIDER_UNAVAILABLE"
    assert "provider-secret" not in rendered
    assert case.request.resume_text not in rendered
    assert "secret-token" not in rendered
    assert result["runMetadata"] is None


def _source_case(case_id: str) -> SourceCase:
    return SourceCase.model_validate(
        {
            "id": case_id,
            "tags": ["synthetic"],
            "requirements": [
                {
                    "requirementId": "requirement:0",
                    "text": "Java delivery",
                    "predictedStatus": "supported",
                    "evidenceIds": ["resume:0"],
                    "verifierVersion": "verifier-v1",
                }
            ],
            "evidence": [
                {"evidenceId": "resume:0", "excerpt": "Delivered Java services."}
            ],
        }
    )


def test_annotation_source_writes_only_for_complete_ordered_passing_selection(
    tmp_path: Path,
) -> None:
    _, cases, _ = load_agent_eval_cases(
        Path(__file__).parents[1] / "evals" / "cases.jsonl"
    )
    selected = cases[:2]
    output = tmp_path / "private" / "source.json"
    exported = [_source_case(case.case_id) for case in selected]

    assert write_annotation_source_if_complete(
        output=output,
        dataset_version="dataset-v1",
        dataset_sha256="a" * 64,
        selected_cases=selected,
        results=[
            _result(selected[0].case_id, selected[0].tags, 1, 70),
            _result(selected[1].case_id, selected[1].tags, 1, 60),
        ],
        annotation_cases=exported,
    )

    parsed = AnnotationSource.model_validate_json(output.read_text(encoding="utf-8"))
    assert [case.case_id for case in parsed.cases] == [case.case_id for case in selected]

    with pytest.raises(FileExistsError):
        write_annotation_source_if_complete(
            output=output,
            dataset_version="dataset-v1",
            dataset_sha256="a" * 64,
            selected_cases=selected,
            results=[
                _result(selected[0].case_id, selected[0].tags, 1, 70),
                _result(selected[1].case_id, selected[1].tags, 1, 60),
            ],
            annotation_cases=exported,
        )


def test_annotation_source_does_not_write_partial_failed_or_reordered_data(
    tmp_path: Path,
) -> None:
    _, cases, _ = load_agent_eval_cases(
        Path(__file__).parents[1] / "evals" / "cases.jsonl"
    )
    selected = cases[:2]
    output = tmp_path / "source.json"
    failed = _result(selected[1].case_id, selected[1].tags, 1, 60)
    failed["passed"] = False

    assert not write_annotation_source_if_complete(
        output=output,
        dataset_version="dataset-v1",
        dataset_sha256="a" * 64,
        selected_cases=selected,
        results=[_result(selected[0].case_id, selected[0].tags, 1, 70), failed],
        annotation_cases=[_source_case(case.case_id) for case in selected],
    )
    assert not output.exists()

    assert not write_annotation_source_if_complete(
        output=output,
        dataset_version="dataset-v1",
        dataset_sha256="a" * 64,
        selected_cases=selected,
        results=[
            _result(selected[0].case_id, selected[0].tags, 1, 70),
            _result(selected[1].case_id, selected[1].tags, 1, 60),
        ],
        annotation_cases=[_source_case(case.case_id) for case in reversed(selected)],
    )
    assert not output.exists()
