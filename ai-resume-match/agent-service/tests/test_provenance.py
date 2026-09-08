from agent_service.models import AnalysisRequest
from agent_service.provenance import analysis_input_fingerprint


EXPECTED_VECTOR = "6d4c96fb440708f7e456e0598d60ea1b940fd2441362c792961f8bd89345b5d1"


def request(*, task_id: int = 12, correlation_id: str = "correlation-12") -> AnalysisRequest:
    return AnalysisRequest(
        taskId=task_id,
        resumeText="Java Redis RabbitMQ",
        jobTitle="Agent Engineer",
        jobDescription="Need Java and tool calling",
        skillTags=["Java", "Agent"],
        correlationId=correlation_id,
    )


def test_fingerprint_matches_cross_language_length_prefixed_vector() -> None:
    assert analysis_input_fingerprint(request()) == EXPECTED_VECTOR


def test_fingerprint_excludes_correlation_id_but_is_task_scoped() -> None:
    assert analysis_input_fingerprint(request(correlation_id="another-correlation")) == EXPECTED_VECTOR
    assert analysis_input_fingerprint(request(task_id=13)) != EXPECTED_VECTOR


def test_fingerprint_uses_normalized_request_content() -> None:
    normalized = request().model_copy(update={"resume_text": " Java Redis RabbitMQ\x00 "})

    assert analysis_input_fingerprint(request()) != analysis_input_fingerprint(normalized)
    assert analysis_input_fingerprint(
        AnalysisRequest(
            taskId=12,
            resumeText=" Java Redis RabbitMQ\x00 ",
            jobTitle="Agent Engineer",
            jobDescription="Need Java and tool calling",
            skillTags=["Java", "Agent"],
        )
    ) == EXPECTED_VECTOR
