import json

import pytest
from pydantic import ValidationError

from agent_service.claim_evidence_evaluation import (
    AdjudicationSubmission,
    AnnotationSource,
    AnnotationSubmission,
    AnnotationTaskSet,
    PredictionKey,
    build_adjudication_template,
    build_annotation_package,
    build_annotation_template,
    evaluate_annotations,
    evaluate_quality_gates,
    extract_source_case,
)


def source() -> AnnotationSource:
    return AnnotationSource.model_validate(
        {
            "datasetVersion": "human-eval-source-v1",
            "datasetSha256": "a" * 64,
            "cases": [
                {
                    "id": "case-1",
                    "tags": ["english", "synthetic"],
                    "requirements": [
                        {
                            "requirementId": "requirement:0",
                            "text": "Production Java experience",
                            "predictedStatus": "supported",
                            "evidenceIds": ["resume:0"],
                            "verifierVersion": "verifier-v1",
                        },
                        {
                            "requirementId": "requirement:1",
                            "text": "Kubernetes operations",
                            "predictedStatus": "partial",
                            "evidenceIds": ["resume:1"],
                            "verifierVersion": "verifier-v1",
                        },
                        {
                            "requirementId": "requirement:2",
                            "text": "GraphQL delivery",
                            "predictedStatus": "not_found",
                            "evidenceIds": [],
                            "verifierVersion": "verifier-v1",
                        },
                    ],
                    "evidence": [
                        {
                            "evidenceId": "resume:0",
                            "excerpt": "Built production services with Java 21.",
                        },
                        {
                            "evidenceId": "resume:1",
                            "excerpt": "Completed a Kubernetes workshop.",
                        },
                    ],
                }
            ],
        }
    )


def annotations(task_hash: str, pair_ids: list[str]):
    annotator_a = AnnotationSubmission.model_validate(
        {
            "taskSetSha256": task_hash,
            "annotatorId": "reviewer_a",
            "labels": [
                {"pairId": pair_ids[0], "label": "supported", "confidence": 3},
                {
                    "pairId": pair_ids[1],
                    "label": "partial",
                    "confidence": 2,
                    "notes": "PRIVATE_ANNOTATOR_NOTE",
                },
                {"pairId": pair_ids[2], "label": "unsupported", "confidence": 3},
            ],
        }
    )
    annotator_b = AnnotationSubmission.model_validate(
        {
            "taskSetSha256": task_hash,
            "annotatorId": "reviewer_b",
            "labels": [
                {"pairId": pair_ids[0], "label": "supported", "confidence": 3},
                {"pairId": pair_ids[1], "label": "unsupported", "confidence": 2},
                {"pairId": pair_ids[2], "label": "unsupported", "confidence": 3},
            ],
        }
    )
    adjudication = AdjudicationSubmission.model_validate(
        {
            "taskSetSha256": task_hash,
            "adjudicatorId": "adjudicator_c",
            "resolutions": [
                {
                    "pairId": pair_ids[1],
                    "label": "partial",
                    "rationale": "The excerpt demonstrates learning but not operations.",
                }
            ],
        }
    )
    return annotator_a, annotator_b, adjudication


def test_builds_blinded_tasks_and_separate_prediction_key() -> None:
    task_set, prediction_key = build_annotation_package(source())

    task_payload = json.dumps(task_set.model_dump(by_alias=True), ensure_ascii=False)
    key_payload = json.dumps(prediction_key.model_dump(by_alias=True), ensure_ascii=False)
    assert len(task_set.pairs) == 3
    assert task_set.pairs[2].evidence_excerpts == []
    assert "predictedStatus" not in task_payload
    assert "verifier-v1" not in task_payload
    assert "case-1" not in task_payload
    assert "predictedStatus" in key_payload
    assert "verifier-v1" in key_payload
    assert PredictionKey.model_validate_json(key_payload) == prediction_key
    label_template = build_annotation_template(task_set)
    assert all(item["label"] == "TODO" for item in label_template["labels"])
    with pytest.raises(ValidationError):
        AnnotationSubmission.model_validate(label_template)


def test_task_hash_rejects_content_tampering() -> None:
    task_set, _ = build_annotation_package(source())
    tampered = task_set.model_dump(by_alias=True)
    tampered["pairs"][0]["claim"] = "Changed after annotation assignment"

    with pytest.raises(ValidationError, match="taskSetSha256"):
        AnnotationTaskSet.model_validate(tampered)


def test_evaluates_two_annotators_and_exact_disagreement_adjudication() -> None:
    task_set, prediction_key = build_annotation_package(source())
    pair_ids = [pair.pair_id for pair in task_set.pairs]
    annotator_a, annotator_b, adjudication = annotations(
        task_set.task_set_sha256, pair_ids
    )

    report = evaluate_annotations(
        task_set=task_set,
        prediction_key=prediction_key,
        annotator_a=annotator_a,
        annotator_b=annotator_b,
        adjudication=adjudication,
    )

    assert report["counts"] == {
        "pairs": 3,
        "agreements": 2,
        "disagreements": 1,
        "acceptedPredictions": 2,
        "rejectedPredictions": 1,
    }
    assert report["annotation"]["agreementRate"] == 0.6667
    assert report["annotation"]["cohensKappa"] == 0.5
    assert report["quality"]["unsupportedPositiveClaimRate"] == 0.0
    assert report["quality"]["falseRejectionRate"] == 0.0
    assert report["quality"]["unsupportedPositiveClaimWilson95"] == {
        "lower": 0.0,
        "upper": 0.6576,
    }
    assert report["quality"]["falseRejectionWilson95"] == {
        "lower": 0.0,
        "upper": 0.7935,
    }
    assert report["quality"]["confusionMatrix"]["partial"]["partial"] == 1
    rendered = json.dumps(report, ensure_ascii=False)
    assert "Production Java experience" not in rendered
    assert "PRIVATE_ANNOTATOR_NOTE" not in rendered
    assert "reviewer_a" not in rendered

    gates = evaluate_quality_gates(
        report,
        min_pairs=3,
        min_accepted_predictions=2,
        min_rejected_predictions=1,
        min_kappa=0.5,
        max_unsupported_positive_rate=0.02,
        max_false_rejection_rate=0.10,
    )
    assert gates["passed"] is True
    assert all(gates["checks"].values())


def test_rejects_missing_or_non_independent_adjudication() -> None:
    task_set, prediction_key = build_annotation_package(source())
    pair_ids = [pair.pair_id for pair in task_set.pairs]
    annotator_a, annotator_b, adjudication = annotations(
        task_set.task_set_sha256, pair_ids
    )

    with pytest.raises(ValueError, match="resolve exactly"):
        evaluate_annotations(
            task_set=task_set,
            prediction_key=prediction_key,
            annotator_a=annotator_a,
            annotator_b=annotator_b,
            adjudication=None,
        )
    invalid_adjudicator = adjudication.model_copy(
        update={"adjudicator_id": annotator_a.annotator_id}
    )
    with pytest.raises(ValueError, match="independent"):
        evaluate_annotations(
            task_set=task_set,
            prediction_key=prediction_key,
            annotator_a=annotator_a,
            annotator_b=annotator_b,
            adjudication=invalid_adjudicator,
        )

    template = build_adjudication_template(
        task_set=task_set,
        annotator_a=annotator_a,
        annotator_b=annotator_b,
    )
    assert [item["pairId"] for item in template["resolutions"]] == [pair_ids[1]]
    assert template["resolutions"][0]["label"] == "TODO"
    with pytest.raises(ValidationError):
        AdjudicationSubmission.model_validate(template)


def test_measures_unsupported_positive_claims() -> None:
    task_set, prediction_key = build_annotation_package(source())
    pair_ids = [pair.pair_id for pair in task_set.pairs]
    labels = [
        {"pairId": pair_ids[0], "label": "supported", "confidence": 3},
        {"pairId": pair_ids[1], "label": "unsupported", "confidence": 3},
        {"pairId": pair_ids[2], "label": "unsupported", "confidence": 3},
    ]
    first = AnnotationSubmission(
        task_set_sha256=task_set.task_set_sha256,
        annotator_id="reviewer_a",
        labels=labels,
    )
    second = AnnotationSubmission(
        task_set_sha256=task_set.task_set_sha256,
        annotator_id="reviewer_b",
        labels=labels,
    )

    report = evaluate_annotations(
        task_set=task_set,
        prediction_key=prediction_key,
        annotator_a=first,
        annotator_b=second,
        adjudication=AdjudicationSubmission(
            task_set_sha256=task_set.task_set_sha256,
            adjudicator_id="adjudicator_c",
            resolutions=[],
        ),
    )

    assert report["quality"]["unsupportedPositiveClaims"] == 1
    assert report["quality"]["unsupportedPositiveClaimRate"] == 0.5


def test_zero_denominators_are_not_reported_as_zero_error_rates() -> None:
    task_set, prediction_key = build_annotation_package(source())
    pair_ids = [pair.pair_id for pair in task_set.pairs]
    all_rejected_key = prediction_key.model_copy(
        update={
            "items": [
                item.model_copy(update={"predicted_status": "not_found"})
                for item in prediction_key.items
            ]
        }
    )
    labels = [
        {"pairId": pair_id, "label": "unsupported", "confidence": 3}
        for pair_id in pair_ids
    ]
    first = AnnotationSubmission(
        task_set_sha256=task_set.task_set_sha256,
        annotator_id="reviewer_a",
        labels=labels,
    )
    second = AnnotationSubmission(
        task_set_sha256=task_set.task_set_sha256,
        annotator_id="reviewer_b",
        labels=labels,
    )

    report = evaluate_annotations(
        task_set=task_set,
        prediction_key=all_rejected_key,
        annotator_a=first,
        annotator_b=second,
        adjudication=None,
    )

    assert report["quality"]["unsupportedPositiveClaimRate"] is None
    assert report["quality"]["unsupportedPositiveClaimWilson95"] is None
    gates = evaluate_quality_gates(
        report,
        min_pairs=1,
        min_accepted_predictions=1,
        min_rejected_predictions=1,
        min_kappa=0.0,
        max_unsupported_positive_rate=1.0,
        max_false_rejection_rate=1.0,
    )
    assert gates["checks"]["minimumAcceptedPredictions"] is False
    assert gates["checks"]["unsupportedPositiveClaimRate"] is False
    assert gates["passed"] is False


def test_extracts_only_requirement_referenced_evidence_from_agent_response() -> None:
    extracted = extract_source_case(
        case_id="case-1",
        tags=["synthetic"],
        response={
            "requirementResults": [
                {
                    "requirementId": "requirement:0",
                    "text": "Java delivery",
                    "status": "supported",
                    "evidenceIds": ["resume:0"],
                    "verification": {"verifierVersion": "verifier-v1"},
                }
            ],
            "structuredReport": {
                "evidence": [
                    {
                        "evidenceId": "resume:0",
                        "excerpt": "Delivered Java services.",
                        "score": 0.9,
                        "sourceStart": 0,
                        "sourceEnd": 24,
                    },
                    {
                        "evidenceId": "resume:9",
                        "excerpt": "Unreferenced excerpt must be omitted.",
                        "score": 0.8,
                    },
                ]
            },
        },
    )

    assert [item.evidence_id for item in extracted.evidence] == ["resume:0"]
    assert extracted.evidence[0].excerpt == "Delivered Java services."
