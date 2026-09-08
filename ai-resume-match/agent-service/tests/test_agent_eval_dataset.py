from __future__ import annotations

import json
from pathlib import Path

import pytest

from agent_service.evaluation import load_agent_eval_cases
from evals.build_agent_dataset import DATASET_VERSION, build_cases


DATASET_PATH = Path(__file__).parents[1] / "evals" / "cases.jsonl"


def test_committed_live_agent_dataset_matches_canonical_builder() -> None:
    expected = "\n".join(
        json.dumps(case, ensure_ascii=False, separators=(",", ":"))
        for case in build_cases()
    ) + "\n"

    assert DATASET_PATH.read_text(encoding="utf-8") == expected


def test_live_agent_dataset_has_layered_slices_and_stable_hash() -> None:
    version, cases, digest = load_agent_eval_cases(DATASET_PATH)

    assert version == DATASET_VERSION
    assert len(cases) == 120
    assert digest == "70ccc3413f58196e082c2dc183190c8427304e1427adef0a954f19fa75924f66"
    for tag in (
        "strong",
        "partial",
        "no-evidence",
        "negation",
    ):
        assert sum(tag in case.tags for case in cases) >= 10
    assert sum("resume-injection" in case.tags for case in cases) == 25
    assert sum("jd-injection" in case.tags for case in cases) == 25
    for tag in (
        "long-document",
        "format-noise",
        "temporal-boundary",
        "acronym-synonym",
        "forged-evidence",
        "protocol-adversarial",
    ):
        assert sum(tag in case.tags for case in cases) == 5
    assert sum("security" in case.tags for case in cases) == 60
    assert sum("adversarial" in case.tags for case in cases) == 60
    assert sum(case.expectations.expect_no_positive_claims for case in cases) == 12


def test_security_slice_forbids_every_attack_success_marker() -> None:
    _, cases, _ = load_agent_eval_cases(DATASET_PATH)
    security_cases = [case for case in cases if "security" in case.tags]

    assert len(security_cases) == 60
    assert all(case.expectations.forbidden_terms for case in security_cases)
    assert all(
        case.expectations.max_score <= 65
        for case in security_cases
    )


def test_live_agent_dataset_rejects_duplicate_task_ids(tmp_path: Path) -> None:
    cases = build_cases()[:2]
    cases[1]["request"]["taskId"] = cases[0]["request"]["taskId"]
    path = tmp_path / "duplicates.jsonl"
    path.write_text(
        "\n".join(json.dumps(case, ensure_ascii=False) for case in cases),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="taskIds must be unique"):
        load_agent_eval_cases(path)


def test_no_evidence_expectation_requires_conservative_score_cap(tmp_path: Path) -> None:
    case = build_cases()[20]
    case["expectations"]["maxScore"] = 31
    path = tmp_path / "invalid.jsonl"
    path.write_text(json.dumps(case, ensure_ascii=False), encoding="utf-8")

    with pytest.raises(ValueError, match="no-positive-claim cases"):
        load_agent_eval_cases(path)
