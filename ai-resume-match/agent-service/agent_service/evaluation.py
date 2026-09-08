from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from agent_service.models import (
    AnalysisRequest,
    JobRequirement,
    RequirementAssessment,
    RequirementResult,
    to_camel,
)
from agent_service.requirements import calculate_match_score


SECTION_PATTERN = re.compile(r"(?ms)^## (?P<title>[^\n]+)\n(?P<body>.*?)(?=^## |\Z)")
CLAIM_PATTERN = re.compile(
    r"^- .+ _\(evidence: (?P<ids>`resume:\d+`(?:, `resume:\d+`)*)\)_$"
)
EVIDENCE_PATTERN = re.compile(
    r"^- `(?P<id>resume:\d+)` \| score=(?P<score>\d+\.\d{4}) \| excerpt: .+$"
)
CORE_EMPTY = "- 未形成有证据支持的正向结论。"
SKILLS_EMPTY = "- 未检索到可验证的匹配技能。"
EVIDENCE_EMPTY = "- 无被引用的简历证据。"


class AgentEvaluationModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        serialize_by_alias=True,
        extra="forbid",
    )


class AgentEvalExpectations(AgentEvaluationModel):
    min_score: int = Field(default=0, ge=0, le=100)
    max_score: int = Field(default=100, ge=0, le=100)
    required_tools: list[str] = Field(
        default_factory=lambda: [
            "get_job_requirements",
            "search_resume_evidence",
            "submit_match_report",
        ],
        min_length=1,
        max_length=10,
    )
    required_terms: list[str] = Field(default_factory=list, max_length=20)
    forbidden_terms: list[str] = Field(default_factory=list, max_length=20)
    expect_no_positive_claims: bool = False
    max_steps: int = Field(default=6, ge=1, le=20)
    max_latency_ms: int = Field(default=60_000, ge=1, le=300_000)

    @field_validator("required_tools", "required_terms", "forbidden_terms")
    @classmethod
    def reject_blank_or_duplicate_values(cls, values: list[str]) -> list[str]:
        cleaned = [value.strip() for value in values]
        if any(not value for value in cleaned):
            raise ValueError("evaluation string lists must not contain blanks")
        folded = [value.casefold() for value in cleaned]
        if len(folded) != len(set(folded)):
            raise ValueError("evaluation string lists must not contain duplicates")
        return cleaned

    @model_validator(mode="after")
    def validate_score_range(self) -> AgentEvalExpectations:
        if self.min_score > self.max_score:
            raise ValueError("minScore must not exceed maxScore")
        if self.expect_no_positive_claims and self.max_score > 30:
            raise ValueError("no-positive-claim cases must cap maxScore at 30")
        return self


class AgentEvalCase(AgentEvaluationModel):
    dataset_version: str = Field(min_length=1, max_length=120)
    case_id: str = Field(alias="id", min_length=1, max_length=120)
    tags: list[str] = Field(min_length=1, max_length=20)
    request: AnalysisRequest
    expectations: AgentEvalExpectations

    @field_validator("tags")
    @classmethod
    def validate_tags(cls, values: list[str]) -> list[str]:
        cleaned = [value.strip().casefold() for value in values]
        if any(not value for value in cleaned):
            raise ValueError("tags must not contain blanks")
        if len(cleaned) != len(set(cleaned)):
            raise ValueError("tags must not contain duplicates")
        return cleaned


def load_agent_eval_cases(path: Path) -> tuple[str, list[AgentEvalCase], str]:
    cases: list[AgentEvalCase] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            cases.append(AgentEvalCase.model_validate_json(line))
        except ValueError as exc:
            raise ValueError(f"invalid Agent eval case at line {line_number}: {exc}") from exc
    if not cases:
        raise ValueError("Agent eval dataset must contain at least one case")

    versions = {case.dataset_version for case in cases}
    if len(versions) != 1:
        raise ValueError("all Agent eval cases must use the same datasetVersion")
    case_ids = [case.case_id for case in cases]
    if len(case_ids) != len(set(case_ids)):
        raise ValueError("Agent eval case ids must be unique")
    task_ids = [case.request.task_id for case in cases]
    if len(task_ids) != len(set(task_ids)):
        raise ValueError("Agent eval taskIds must be unique")

    canonical = json.dumps(
        [case.model_dump(by_alias=True) for case in cases],
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return versions.pop(), cases, hashlib.sha256(canonical).hexdigest()


def _sections(report: str) -> dict[str, list[str]]:
    return {
        match.group("title"): [
            line for line in match.group("body").strip().splitlines() if line.strip()
        ]
        for match in SECTION_PATTERN.finditer(report)
    }


def _claim_references(lines: list[str], empty_marker: str) -> set[str] | None:
    if lines == [empty_marker]:
        return set()
    if not lines or empty_marker in lines:
        return None

    references: set[str] = set()
    for line in lines:
        match = CLAIM_PATTERN.fullmatch(line)
        if match is None:
            return None
        references.update(re.findall(r"resume:\d+", match.group("ids")))
    return references


def evaluate_grounding(report: str, score: int) -> bool:
    """Verify claim-level citations and their rendered evidence mappings.

    This checks the report contract produced by ToolRegistry rather than accepting
    the mere presence of a ``resume:`` substring. Runtime tool validation separately
    guarantees that mapped IDs originated from this Agent run's retrieval results.
    """

    sections = _sections(report)
    core_references = _claim_references(sections.get("核心结论", []), CORE_EMPTY)
    skill_references = _claim_references(sections.get("已匹配技能", []), SKILLS_EMPTY)
    if core_references is None or skill_references is None:
        return False

    references = core_references | skill_references
    mapping_lines = sections.get("证据引用与原文映射", [])
    if not references:
        return score <= 30 and mapping_lines == [EVIDENCE_EMPTY]
    if not mapping_lines or EVIDENCE_EMPTY in mapping_lines:
        return False

    mapped: set[str] = set()
    for line in mapping_lines:
        match = EVIDENCE_PATTERN.fullmatch(line)
        if match is None or match.group("id") in mapped:
            return False
        if not 0 < float(match.group("score")) <= 1:
            return False
        mapped.add(match.group("id"))
    return references == mapped


def evaluate_no_positive_claims(report: str) -> bool:
    sections = _sections(report)
    return (
        _claim_references(sections.get("核心结论", []), CORE_EMPTY) == set()
        and _claim_references(sections.get("已匹配技能", []), SKILLS_EMPTY) == set()
    )


def evaluate_deterministic_score(response: dict[str, Any], score: int) -> bool:
    try:
        results = [
            RequirementResult.model_validate(item)
            for item in response.get("requirementResults", [])
        ]
        if not results:
            return False
        verifier_version = response.get("verifierVersion")
        if not isinstance(verifier_version, str) or any(
            result.verification.verifier_version != verifier_version
            for result in results
        ):
            return False
        requirements = [
            JobRequirement.model_validate(
                {
                    "requirementId": result.requirement_id,
                    "text": result.text,
                    "mustHave": result.must_have,
                    "weight": result.weight,
                }
            )
            for result in results
        ]
        assessments = [
            RequirementAssessment.model_validate(
                {
                    "requirementId": result.requirement_id,
                    "status": result.status,
                    "explanation": result.explanation,
                    "evidenceIds": result.evidence_ids,
                }
            )
            for result in results
        ]
        return calculate_match_score(requirements, assessments) == score
    except (TypeError, ValueError):
        return False


def evaluate_response(
    response: dict[str, Any],
    expectations: dict[str, Any],
    latency_ms: int,
) -> dict[str, bool]:
    score = int(response.get("matchScore", -1))
    report = str(response.get("reportMarkdown", ""))
    tool_names = [item.get("name") for item in response.get("toolTrace", [])]
    required_tools = expectations.get(
        "requiredTools",
        ["get_job_requirements", "search_resume_evidence", "submit_match_report"],
    )

    cursor = -1
    ordered = True
    for tool in required_tools:
        try:
            cursor = tool_names.index(tool, cursor + 1)
        except ValueError:
            ordered = False
            break

    required_terms = [str(value).casefold() for value in expectations.get("requiredTerms", [])]
    forbidden_terms = [str(value).casefold() for value in expectations.get("forbiddenTerms", [])]
    # Term assertions apply only to Agent-authored positive claims and advice.
    # Exclude the requirement breakdown/gaps because they can repeat untrusted JD
    # text, and exclude evidence mappings because they quote untrusted resume text.
    sections = _sections(report)
    assertion_report = "\n".join(
        line
        for title in ("核心结论", "已匹配技能", "改进建议", "模拟面试题")
        for line in sections.get(title, [])
    )
    folded_report = assertion_report.casefold()

    return {
        "scoreRange": expectations.get("minScore", 0) <= score <= expectations.get("maxScore", 100),
        "toolOrder": ordered,
        "allToolsSucceeded": all(
            item.get("outcome") == "success" for item in response.get("toolTrace", [])
        ),
        "versionMetadata": all(
            isinstance(response.get(field), str) and bool(response[field].strip())
            for field in ("model", "promptVersion", "retrieverVersion", "verifierVersion")
        ),
        "groundedEvidence": evaluate_grounding(report, score),
        "deterministicScore": evaluate_deterministic_score(response, score),
        "requiredTerms": all(term in folded_report for term in required_terms),
        "forbiddenTerms": all(term not in folded_report for term in forbidden_terms),
        "noPositiveClaims": (
            not expectations.get("expectNoPositiveClaims", False)
            or evaluate_no_positive_claims(report)
        ),
        "stepBound": int(response.get("steps", 999)) <= expectations.get("maxSteps", 6),
        "latencyBound": latency_ms <= expectations.get("maxLatencyMs", 60_000),
    }
