from agent_service.models import JobRequirement
from agent_service.retrieval import Evidence
from agent_service.verification import ConservativeLexicalEvidenceVerifier


def requirement(text: str) -> JobRequirement:
    return JobRequirement(
        requirementId="requirement:0",
        text=text,
        mustHave=True,
        weight=2,
    )


def evidence(text: str, evidence_id: str = "resume:0") -> Evidence:
    return Evidence(evidence_id=evidence_id, excerpt=text, score=0.8)


def test_supports_single_technology_term_in_positive_evidence() -> None:
    decision = ConservativeLexicalEvidenceVerifier().verify(
        requirement("Kubernetes"),
        [evidence("Operated Kubernetes clusters in production.")],
    )

    assert decision.status == "supported"
    assert decision.term_coverage == 1.0
    assert decision.evidence_ids == ["resume:0"]


def test_rejects_english_negated_evidence() -> None:
    decision = ConservativeLexicalEvidenceVerifier().verify(
        requirement("Kubernetes"),
        [evidence("This project did not use Kubernetes and ran only with Docker Compose.")],
    )

    assert decision.status == "not_found"
    assert decision.reason == "negated_evidence_only"
    assert decision.evidence_ids == []


def test_rejects_chinese_negated_evidence() -> None:
    decision = ConservativeLexicalEvidenceVerifier().verify(
        requirement("向量数据库"),
        [evidence("项目尚未接入向量数据库，当前仅使用 hashing 检索。")],
    )

    assert decision.status == "not_found"
    assert decision.reason == "negated_evidence_only"


def test_partial_when_only_some_requirement_terms_are_supported() -> None:
    decision = ConservativeLexicalEvidenceVerifier().verify(
        requirement("Java RabbitMQ observability"),
        [evidence("Built Java services and unit tests.")],
    )

    assert decision.status == "partial"
    assert decision.term_coverage == 0.3333


def test_mixed_positive_and_negated_evidence_is_never_fully_supported() -> None:
    decision = ConservativeLexicalEvidenceVerifier().verify(
        requirement("Kubernetes"),
        [
            evidence("Operated Kubernetes in a lab.", "resume:0"),
            evidence("The portfolio project did not use Kubernetes.", "resume:1"),
        ],
    )

    assert decision.status == "partial"
    assert decision.reason == "mixed_positive_and_negated_evidence"
    assert decision.evidence_ids == ["resume:0"]


def test_does_not_treat_no_downtime_as_negating_kubernetes() -> None:
    decision = ConservativeLexicalEvidenceVerifier().verify(
        requirement("Kubernetes"),
        [evidence("Delivered a no downtime migration to Kubernetes.")],
    )

    assert decision.status == "supported"


def test_does_not_treat_chinese_stateless_phrase_as_negation() -> None:
    decision = ConservativeLexicalEvidenceVerifier().verify(
        requirement("Kubernetes"),
        [evidence("开发无状态服务并使用 Kubernetes 部署。")],
    )

    assert decision.status == "supported"
