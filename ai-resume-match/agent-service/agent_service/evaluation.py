from __future__ import annotations

import re
from typing import Any


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
    # Evaluation assertions apply to the Agent's report claims, not to quoted,
    # untrusted resume excerpts in the audit mapping. Otherwise an injected phrase
    # in the source document could create a false pass or false failure.
    assertion_report = report.split("## 证据引用与原文映射", 1)[0]
    folded_report = assertion_report.casefold()

    return {
        "scoreRange": expectations.get("minScore", 0) <= score <= expectations.get("maxScore", 100),
        "toolOrder": ordered,
        "allToolsSucceeded": all(
            item.get("outcome") == "success" for item in response.get("toolTrace", [])
        ),
        "groundedEvidence": evaluate_grounding(report, score),
        "requiredTerms": all(term in folded_report for term in required_terms),
        "forbiddenTerms": all(term not in folded_report for term in forbidden_terms),
        "stepBound": int(response.get("steps", 999)) <= expectations.get("maxSteps", 6),
        "latencyBound": latency_ms <= expectations.get("maxLatencyMs", 90_000),
    }
