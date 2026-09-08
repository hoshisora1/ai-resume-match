from agent_service.models import RequirementAssessment
from agent_service.requirements import calculate_match_score, extract_requirements


def test_extracts_at_most_five_stable_requirements_from_tags() -> None:
    requirements = extract_requirements(
        job_title="AI Engineer",
        job_description="Must have Java, Python, RAG, RabbitMQ, Redis and Docker.",
        skill_tags=["Java", "Python", "RAG", "RabbitMQ", "Redis", "Docker"],
    )

    assert [item.requirement_id for item in requirements] == [
        "requirement:0",
        "requirement:1",
        "requirement:2",
        "requirement:3",
        "requirement:4",
    ]
    assert [item.text for item in requirements] == [
        "Java",
        "Python",
        "RAG",
        "RabbitMQ",
        "Redis",
    ]
    assert all(item.must_have and item.weight == 2 for item in requirements)


def test_extracts_marker_clauses_when_skill_tags_are_empty() -> None:
    requirements = extract_requirements(
        job_title="Agent Engineer",
        job_description="Requirements: Python, Tool Calling and evaluation; Nice to have: React",
        skill_tags=[],
    )

    assert [item.text for item in requirements] == [
        "Python",
        "Tool Calling",
        "evaluation",
        "React",
    ]
    assert requirements[0].must_have is True
    assert requirements[-1].must_have is False


def test_deterministic_score_caps_missing_must_have() -> None:
    requirements = extract_requirements(
        job_title="Backend Engineer",
        job_description="Must have Java and RabbitMQ. Preferred React, Vue and CSS.",
        skill_tags=["Java", "RabbitMQ", "React", "Vue", "CSS"],
    )
    assessments = [
        RequirementAssessment(
            requirementId="requirement:0",
            status="supported",
            explanation="Java evidence",
            evidenceIds=["resume:0"],
        ),
        RequirementAssessment(
            requirementId="requirement:1",
            status="not_found",
            explanation="No RabbitMQ evidence",
            evidenceIds=[],
        ),
        RequirementAssessment(
            requirementId="requirement:2",
            status="supported",
            explanation="React evidence",
            evidenceIds=["resume:1"],
        ),
        RequirementAssessment(
            requirementId="requirement:3",
            status="supported",
            explanation="Vue evidence",
            evidenceIds=["resume:2"],
        ),
        RequirementAssessment(
            requirementId="requirement:4",
            status="supported",
            explanation="CSS evidence",
            evidenceIds=["resume:3"],
        ),
    ]

    # Raw weighted score rounds to 71, then the missing-must-have cap applies.
    assert calculate_match_score(requirements, assessments) == 69


def test_equal_weight_partial_status_scores_fifty() -> None:
    requirements = extract_requirements(
        job_title="Agent Engineer",
        job_description="Need Java and RAG.",
        skill_tags=["Java", "RAG"],
    )
    assessments = [
        RequirementAssessment(
            requirementId=item.requirement_id,
            status="partial",
            explanation="Some evidence",
            evidenceIds=["resume:0"],
        )
        for item in requirements
    ]

    assert calculate_match_score(requirements, assessments) == 50
