import json

import pytest
from pydantic import ValidationError

from agent_service.models import AnalysisRequest, JobRequirement, RequirementAssessment
from agent_service.requirements import calculate_match_score, extract_requirements
from agent_service.retrieval import Evidence, HashingRetriever
from agent_service.tools import SearchResumeArguments, ToolContext, ToolRegistry
from agent_service.verification import ConservativeLexicalEvidenceVerifier


def test_every_tool_object_has_only_required_properties_including_nested_definitions():
    def check(node):
        if isinstance(node, dict):
            if node.get("type") == "object":
                assert node["additionalProperties"] is False
                assert set(node.get("properties", {})) == set(node.get("required", []))
            for value in node.values():
                check(value)
        elif isinstance(node, list):
            for item in node:
                check(item)

    for tool in ToolRegistry().definitions():
        assert tool["function"]["strict"] is True
        check(tool["function"]["parameters"])


def test_tool_inputs_do_not_silently_supply_missing_required_fields():
    with pytest.raises(ValidationError):
        SearchResumeArguments(requirementId="requirement:0", query="Java")
    with pytest.raises(ValidationError):
        RequirementAssessment(requirementId="requirement:0", status="not_found", explanation="No evidence")


def test_empty_tool_arguments_are_rejected_as_invalid_json():
    request = AnalysisRequest(taskId=1, resumeText="Java", jobTitle="Engineer", jobDescription="Java")
    context = ToolContext(request=request, retriever=HashingRetriever(request.resume_text))
    registry = ToolRegistry()
    result = registry.execute(registry.GET_JOB_REQUIREMENTS, "", context)
    assert result.outcome == "denied"
    assert result.output["error"] == "invalid_json_arguments"


def test_explicit_headings_scope_numbered_items_and_preserve_technology_punctuation():
    requirements = extract_requirements(
        job_title="Engineer",
        job_description="Requirements:\n1. .NET\n2. Node.js\nNice to have:\n- Docker",
        skill_tags=["Docker"],
    )
    assert [(item.text, item.must_have) for item in requirements] == [
        (".NET", True), ("Node.js", True), ("Docker", False)
    ]


@pytest.mark.parametrize("description", [
    "Must have Python, React, Docker and three years of experience.",
    "必须掌握 Python、React、Docker；必须具有三年开发经验。",
])
def test_partial_tag_list_does_not_erase_other_job_requirements(description):
    requirements = extract_requirements(
        job_title="Full stack engineer", job_description=description, skill_tags=["Docker"]
    )
    assert len(requirements) == 4
    assert all(item.must_have for item in requirements)
    assert {"Python", "React", "Docker"}.issubset({item.text for item in requirements})
    assessments = [RequirementAssessment(
        requirementId=item.requirement_id,
        status="supported" if item.text == "Docker" else "not_found",
        explanation="Only Docker is evidenced.",
        evidenceIds=["resume:0"] if item.text == "Docker" else [],
    ) for item in requirements]
    assert calculate_match_score(requirements, assessments) == 25


@pytest.mark.parametrize("description", [
    "Preferred Redis. Required Java.",
    "Redis preferred.\nJava required.",
    "Redis 加分。必须掌握 Java。",
    "Redis 优先；Java 必须。",
])
def test_modality_stays_with_its_own_clause(description):
    requirements = extract_requirements(
        job_title="Backend engineer", job_description=description, skill_tags=["Java", "Redis"]
    )
    assert [(item.text, item.must_have, item.weight) for item in requirements] == [
        ("Java", True, 2), ("Redis", False, 1)
    ]


def test_required_items_take_priority_over_optional_items_at_the_five_item_budget():
    requirements = extract_requirements(
        job_title="Backend engineer",
        job_description="Preferred Redis, Docker, Kafka, React, CSS. Required Python.",
        skill_tags=["Redis", "Docker", "Kafka", "React", "CSS"],
    )
    assert len(requirements) == 5
    assert requirements[0].text == "Python"
    assert requirements[0].must_have


@pytest.mark.parametrize(("term", "excerpt"), [
    ("Java", "Built frontend applications with JavaScript and React."),
    ("SQL", "Built a NoSQL document store with MongoDB."),
    ("RAG", "Built a storage service."),
    ("C", "Implemented C++ services."),
])
def test_technology_substrings_are_not_supporting_evidence(term, excerpt):
    result = ConservativeLexicalEvidenceVerifier().verify(
        JobRequirement(requirementId="requirement:0", text=term, mustHave=True, weight=2),
        [Evidence("resume:0", excerpt, 0.8)],
    )
    assert result.status == "not_found"
    assert result.evidence_ids == []


@pytest.mark.parametrize(("term", "excerpt"), [
    ("Java", "熟悉Java，开发过后端服务。"),
    ("Java", "Built Java-based services."),
    ("C++", "Used C++ for a compiler."),
    ("C#", "Used C# and .NET."),
    ("Node.js", "Used Node.js for an API."),
    ("向量检索", "负责RAG向量检索功能。"),
])
def test_literal_technology_names_keep_punctuation_and_chinese_boundaries(term, excerpt):
    result = ConservativeLexicalEvidenceVerifier().verify(
        JobRequirement(requirementId="requirement:0", text=term, mustHave=True, weight=2),
        [Evidence("resume:0", excerpt, 0.8)],
    )
    assert result.status == "supported"


def test_wrong_java_proposal_is_downgraded_through_the_complete_tool_flow():
    request = AnalysisRequest(
        taskId=1, resumeText="Built frontend applications with JavaScript and React.",
        jobTitle="Java engineer", jobDescription="Must have Java.", skillTags=["Java"],
    )
    registry = ToolRegistry()
    context = ToolContext(request=request, retriever=HashingRetriever(request.resume_text))
    registry.execute(registry.GET_JOB_REQUIREMENTS, "{}", context)
    registry.execute(registry.SEARCH_RESUME_EVIDENCE, json.dumps({
        "requirementId": "requirement:0", "query": "JavaScript React", "topK": 3,
    }), context)
    assert context.evidence
    result = registry.execute(registry.SUBMIT_MATCH_REPORT, json.dumps({
        "requirementAssessments": [{
            "requirementId": "requirement:0", "status": "supported",
            "explanation": "The model incorrectly equates JavaScript with Java.",
            "evidenceIds": list(context.evidence),
        }],
        "recommendations": ["Study Java", "Build an API", "Add tests"],
        "interviewQuestions": ["Explain Java", "Explain testing", "Explain APIs"],
    }), context)
    assert result.outcome == "success"
    assert result.final_report.match_score == 0
    assert result.final_report.core_claims == []
    assert result.final_requirement_results[0].status == "not_found"
