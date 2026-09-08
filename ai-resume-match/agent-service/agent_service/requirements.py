from __future__ import annotations

import math
import re
from collections.abc import Sequence

from agent_service.models import JobRequirement, RequirementAssessment, RequirementResult, RequirementStatus
from agent_service.retrieval import literal_term_pattern


MAX_REQUIREMENTS = 5
MUST_HAVE_WEIGHT = 2
OPTIONAL_WEIGHT = 1
MISSING_MUST_HAVE_SCORE_CAP = 69
MODALITY_PATTERN = re.compile(
    r"(?P<optional>\b(?:preferred|nice\s+to\s+have)\b|加分|优先(?:考虑)?)|"
    r"(?P<required>\b(?:must(?:\s+have)?|required|requirements?|needs?)\b|"
    r"(?:要求|必须|需要|任职资格)(?:掌握|熟悉|具备|具有|了解|拥有|精通)?)",
    re.IGNORECASE,
)
CLAUSE_SEPARATOR = re.compile(r"[\r\n。；;!?！？]+|(?<!\d)\.(?=\s|$)")
ITEM_SEPARATOR = re.compile(r"\s*(?:,|，|、|\band\b|以及|与)\s*", re.IGNORECASE)
BULLET_PREFIX = re.compile(r"^\s*(?:[-*•]|\d+[.)、])\s*")


def _clean(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip(" \t\r\n,，;；。:-")


def extract_requirements(
    *,
    job_title: str,
    job_description: str,
    skill_tags: list[str],
) -> list[JobRequirement]:
    """Parse JD clauses first, supplement with tags, then prioritize hard requirements.

    Modality flows within a comma-separated list or an explicit section heading,
    never from a neighboring sentence. The existing five-item budget is retained.
    """
    tags = {_clean(tag).casefold(): _clean(tag) for tag in skill_tags if _clean(tag)}
    candidates: dict[str, tuple[str, bool]] = {}

    def add(text: str, must_have: bool) -> None:
        text = _clean(text)[:240]
        if text:
            key = text.casefold()
            previous = candidates.get(key)
            candidates[key] = (tags.get(key, text), must_have or (previous is not None and previous[1]))

    section_required = False
    for clause in CLAUSE_SEPARATOR.split(job_description):
        clause = BULLET_PREFIX.sub("", clause).strip()
        markers = list(MODALITY_PATTERN.finditer(clause))
        if markers and not _clean(MODALITY_PATTERN.sub("", clause)):
            section_required = markers[-1].lastgroup == "required"
            continue
        must_have = section_required
        for item in ITEM_SEPARATOR.split(clause):
            markers = list(MODALITY_PATTERN.finditer(item))
            if markers:
                must_have = markers[-1].lastgroup == "required"
            add(MODALITY_PATTERN.sub("", item), must_have)

    for tag in tags.values():
        if not any(literal_term_pattern(tag).search(text) for text, _ in candidates.values()):
            add(tag, False)
    if not candidates:
        add(job_title, False)

    selected = sorted(candidates.values(), key=lambda item: not item[1])[:MAX_REQUIREMENTS]
    return [
        JobRequirement(
            requirementId=f"requirement:{index}", text=text, mustHave=must_have,
            weight=MUST_HAVE_WEIGHT if must_have else OPTIONAL_WEIGHT,
        )
        for index, (text, must_have) in enumerate(selected)
    ]


def calculate_match_score(
    requirements: list[JobRequirement],
    assessments: Sequence[RequirementAssessment | RequirementResult],
) -> int:
    if not requirements:
        raise ValueError("at least one requirement is required")
    assessment_by_id = {item.requirement_id: item for item in assessments}
    if len(assessment_by_id) != len(assessments):
        raise ValueError("requirement assessments must be unique")
    expected_ids = {item.requirement_id for item in requirements}
    if set(assessment_by_id) != expected_ids:
        raise ValueError("requirement assessments must cover the extracted requirements exactly")

    factors = {
        RequirementStatus.SUPPORTED: 1.0,
        RequirementStatus.PARTIAL: 0.5,
        RequirementStatus.NOT_FOUND: 0.0,
    }
    total_weight = sum(requirement.weight for requirement in requirements)
    earned = sum(
        requirement.weight * factors[assessment_by_id[requirement.requirement_id].status]
        for requirement in requirements
    )
    score = math.floor((earned * 100 / total_weight) + 0.5)
    if any(
        requirement.must_have
        and assessment_by_id[requirement.requirement_id].status is RequirementStatus.NOT_FOUND
        for requirement in requirements
    ):
        score = min(score, MISSING_MUST_HAVE_SCORE_CAP)
    return score
