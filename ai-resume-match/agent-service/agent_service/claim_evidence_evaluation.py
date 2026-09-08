from __future__ import annotations

import hashlib
import json
import re
from collections import Counter
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from agent_service.models import to_camel


ANNOTATION_SOURCE_SCHEMA = "claim-evidence-source-v1"
ANNOTATION_TASK_SCHEMA = "claim-evidence-task-set-v1"
PREDICTION_KEY_SCHEMA = "claim-evidence-prediction-key-v1"
ANNOTATION_SUBMISSION_SCHEMA = "claim-evidence-annotation-v1"
ADJUDICATION_SCHEMA = "claim-evidence-adjudication-v1"
EVALUATION_RESULT_SCHEMA = "claim-evidence-eval-result-v2"

PredictedStatus = Literal["supported", "partial", "not_found"]
HumanLabel = Literal["supported", "partial", "unsupported"]


class EvaluationModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        serialize_by_alias=True,
        extra="forbid",
    )


def canonical_sha256(value: Any) -> str:
    if isinstance(value, BaseModel):
        value = value.model_dump(by_alias=True, exclude_none=True)
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


class SourceEvidence(EvaluationModel):
    evidence_id: str = Field(pattern=r"^resume:\d+$")
    excerpt: str = Field(min_length=1, max_length=4_000)


class SourceRequirement(EvaluationModel):
    requirement_id: str = Field(pattern=r"^requirement:\d+$")
    text: str = Field(min_length=1, max_length=2_000)
    predicted_status: PredictedStatus
    evidence_ids: list[str] = Field(max_length=10)
    verifier_version: str = Field(min_length=1, max_length=120)

    @field_validator("evidence_ids")
    @classmethod
    def validate_evidence_ids(cls, values: list[str]) -> list[str]:
        if any(re.fullmatch(r"resume:\d+", value) is None for value in values):
            raise ValueError("evidenceIds must use resume IDs")
        if len(values) != len(set(values)):
            raise ValueError("evidenceIds must be unique")
        return values

    @model_validator(mode="after")
    def validate_status_evidence(self) -> SourceRequirement:
        if self.predicted_status == "not_found" and self.evidence_ids:
            raise ValueError("not_found predictions must not cite evidence")
        if self.predicted_status != "not_found" and not self.evidence_ids:
            raise ValueError("positive predictions must cite evidence")
        return self


class SourceCase(EvaluationModel):
    case_id: str = Field(alias="id", min_length=1, max_length=120)
    tags: list[str] = Field(min_length=1, max_length=20)
    requirements: list[SourceRequirement] = Field(min_length=1, max_length=20)
    evidence: list[SourceEvidence] = Field(max_length=100)

    @field_validator("tags")
    @classmethod
    def validate_tags(cls, values: list[str]) -> list[str]:
        normalized = [value.strip() for value in values]
        if any(not value or len(value) > 80 for value in normalized):
            raise ValueError("tags must be non-blank and bounded")
        if len(normalized) != len(set(normalized)):
            raise ValueError("tags must be unique")
        return normalized

    @model_validator(mode="after")
    def validate_graph(self) -> SourceCase:
        requirement_ids = [item.requirement_id for item in self.requirements]
        if len(requirement_ids) != len(set(requirement_ids)):
            raise ValueError("requirement IDs must be unique within a case")
        evidence_ids = [item.evidence_id for item in self.evidence]
        if len(evidence_ids) != len(set(evidence_ids)):
            raise ValueError("evidence IDs must be unique within a case")
        available = set(evidence_ids)
        referenced = {
            evidence_id
            for requirement in self.requirements
            for evidence_id in requirement.evidence_ids
        }
        if not available.issuperset(referenced):
            raise ValueError("every cited evidence ID must resolve within its case")
        if available != referenced:
            raise ValueError("source cases must not contain unreferenced evidence excerpts")
        return self


class AnnotationSource(EvaluationModel):
    schema_version: Literal["claim-evidence-source-v1"] = ANNOTATION_SOURCE_SCHEMA
    dataset_version: str = Field(min_length=1, max_length=120)
    dataset_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    cases: list[SourceCase] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_case_ids(self) -> AnnotationSource:
        case_ids = [case.case_id for case in self.cases]
        if len(case_ids) != len(set(case_ids)):
            raise ValueError("annotation source case IDs must be unique")
        return self


class AnnotationPair(EvaluationModel):
    pair_id: str = Field(pattern=r"^pair:[0-9a-f]{24}$")
    claim: str = Field(min_length=1, max_length=2_000)
    evidence_excerpts: list[str] = Field(max_length=10)

    @field_validator("evidence_excerpts")
    @classmethod
    def validate_excerpts(cls, values: list[str]) -> list[str]:
        if any(not value.strip() or len(value) > 4_000 for value in values):
            raise ValueError("evidence excerpts must be non-blank and bounded")
        return values


class AnnotationTaskSet(EvaluationModel):
    schema_version: Literal["claim-evidence-task-set-v1"] = ANNOTATION_TASK_SCHEMA
    dataset_version: str = Field(min_length=1, max_length=120)
    dataset_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    source_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    task_set_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    pairs: list[AnnotationPair] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_identity(self) -> AnnotationTaskSet:
        pair_ids = [pair.pair_id for pair in self.pairs]
        if len(pair_ids) != len(set(pair_ids)):
            raise ValueError("annotation pair IDs must be unique")
        if self.task_set_sha256 != annotation_task_set_sha256(self):
            raise ValueError("taskSetSha256 does not match the blinded task payload")
        return self


class PredictionKeyItem(EvaluationModel):
    pair_id: str = Field(pattern=r"^pair:[0-9a-f]{24}$")
    case_id: str = Field(min_length=1, max_length=120)
    requirement_id: str = Field(pattern=r"^requirement:\d+$")
    predicted_status: PredictedStatus
    verifier_version: str = Field(min_length=1, max_length=120)


class PredictionKey(EvaluationModel):
    schema_version: Literal["claim-evidence-prediction-key-v1"] = PREDICTION_KEY_SCHEMA
    task_set_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    items: list[PredictionKeyItem] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_pair_ids(self) -> PredictionKey:
        pair_ids = [item.pair_id for item in self.items]
        if len(pair_ids) != len(set(pair_ids)):
            raise ValueError("prediction-key pair IDs must be unique")
        return self


class AnnotationItem(EvaluationModel):
    pair_id: str = Field(pattern=r"^pair:[0-9a-f]{24}$")
    label: HumanLabel
    confidence: int = Field(ge=1, le=3)
    notes: str | None = Field(default=None, max_length=500)


class AnnotationSubmission(EvaluationModel):
    schema_version: Literal["claim-evidence-annotation-v1"] = ANNOTATION_SUBMISSION_SCHEMA
    task_set_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    annotator_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$")
    labels: list[AnnotationItem] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_pair_ids(self) -> AnnotationSubmission:
        pair_ids = [item.pair_id for item in self.labels]
        if len(pair_ids) != len(set(pair_ids)):
            raise ValueError("annotator pair IDs must be unique")
        return self


class AdjudicationItem(EvaluationModel):
    pair_id: str = Field(pattern=r"^pair:[0-9a-f]{24}$")
    label: HumanLabel
    rationale: str = Field(min_length=1, max_length=500)


class AdjudicationSubmission(EvaluationModel):
    schema_version: Literal["claim-evidence-adjudication-v1"] = ADJUDICATION_SCHEMA
    task_set_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    adjudicator_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$")
    resolutions: list[AdjudicationItem]

    @model_validator(mode="after")
    def validate_pair_ids(self) -> AdjudicationSubmission:
        pair_ids = [item.pair_id for item in self.resolutions]
        if len(pair_ids) != len(set(pair_ids)):
            raise ValueError("adjudication pair IDs must be unique")
        return self


def annotation_task_set_sha256(task_set: AnnotationTaskSet) -> str:
    return canonical_sha256(
        {
            "schemaVersion": task_set.schema_version,
            "datasetVersion": task_set.dataset_version,
            "datasetSha256": task_set.dataset_sha256,
            "sourceSha256": task_set.source_sha256,
            "pairs": [pair.model_dump(by_alias=True) for pair in task_set.pairs],
        }
    )


def build_annotation_package(
    source: AnnotationSource,
) -> tuple[AnnotationTaskSet, PredictionKey]:
    source_sha256 = canonical_sha256(source)
    pairs: list[AnnotationPair] = []
    keys: list[PredictionKeyItem] = []
    for case in source.cases:
        evidence_by_id = {item.evidence_id: item.excerpt for item in case.evidence}
        for requirement in case.requirements:
            identity = "\0".join(
                (source.dataset_version, case.case_id, requirement.requirement_id)
            )
            pair_id = "pair:" + hashlib.sha256(identity.encode("utf-8")).hexdigest()[:24]
            pairs.append(
                AnnotationPair(
                    pair_id=pair_id,
                    claim=requirement.text,
                    evidence_excerpts=[
                        evidence_by_id[evidence_id]
                        for evidence_id in requirement.evidence_ids
                    ],
                )
            )
            keys.append(
                PredictionKeyItem(
                    pair_id=pair_id,
                    case_id=case.case_id,
                    requirement_id=requirement.requirement_id,
                    predicted_status=requirement.predicted_status,
                    verifier_version=requirement.verifier_version,
                )
            )

    unsigned = AnnotationTaskSet.model_construct(
        schema_version=ANNOTATION_TASK_SCHEMA,
        dataset_version=source.dataset_version,
        dataset_sha256=source.dataset_sha256,
        source_sha256=source_sha256,
        task_set_sha256="0" * 64,
        pairs=pairs,
    )
    task_set = AnnotationTaskSet(
        schema_version=ANNOTATION_TASK_SCHEMA,
        dataset_version=source.dataset_version,
        dataset_sha256=source.dataset_sha256,
        source_sha256=source_sha256,
        task_set_sha256=annotation_task_set_sha256(unsigned),
        pairs=pairs,
    )
    return task_set, PredictionKey(
        task_set_sha256=task_set.task_set_sha256,
        items=keys,
    )


def build_annotation_template(task_set: AnnotationTaskSet) -> dict[str, Any]:
    """Create an intentionally invalid template that cannot be evaluated before labeling."""

    return {
        "schemaVersion": ANNOTATION_SUBMISSION_SCHEMA,
        "taskSetSha256": task_set.task_set_sha256,
        "annotatorId": "REPLACE_WITH_BLIND_ID",
        "labels": [
            {
                "pairId": pair.pair_id,
                "label": "TODO",
                "confidence": 0,
                "notes": None,
            }
            for pair in task_set.pairs
        ],
    }


def build_adjudication_template(
    *,
    task_set: AnnotationTaskSet,
    annotator_a: AnnotationSubmission,
    annotator_b: AnnotationSubmission,
) -> dict[str, Any]:
    if annotator_a.task_set_sha256 != task_set.task_set_sha256 or (
        annotator_b.task_set_sha256 != task_set.task_set_sha256
    ):
        raise ValueError("annotations must target the supplied task set")
    if annotator_a.annotator_id == annotator_b.annotator_id:
        raise ValueError("two distinct annotator IDs are required")
    pair_ids = [pair.pair_id for pair in task_set.pairs]
    expected = set(pair_ids)
    labels_a = {item.pair_id: item.label for item in annotator_a.labels}
    labels_b = {item.pair_id: item.label for item in annotator_b.labels}
    if set(labels_a) != expected or set(labels_b) != expected:
        raise ValueError("both annotations must cover every task pair")
    return {
        "schemaVersion": ADJUDICATION_SCHEMA,
        "taskSetSha256": task_set.task_set_sha256,
        "adjudicatorId": "REPLACE_WITH_INDEPENDENT_BLIND_ID",
        "resolutions": [
            {
                "pairId": pair_id,
                "label": "TODO",
                "rationale": "TODO",
            }
            for pair_id in pair_ids
            if labels_a[pair_id] != labels_b[pair_id]
        ],
    }


def extract_source_case(
    *,
    case_id: str,
    tags: list[str],
    response: dict[str, Any],
) -> SourceCase:
    raw_requirements = response.get("requirementResults")
    structured = response.get("structuredReport")
    if not isinstance(raw_requirements, list) or not isinstance(structured, dict):
        raise ValueError("Agent response omitted requirementResults or structuredReport")
    raw_evidence = structured.get("evidence")
    if not isinstance(raw_evidence, list):
        raise ValueError("Agent response omitted structured evidence")

    requirements: list[SourceRequirement] = []
    referenced: set[str] = set()
    for raw in raw_requirements:
        if not isinstance(raw, dict):
            raise ValueError("Agent response contained an invalid requirement result")
        verification = raw.get("verification")
        if not isinstance(verification, dict):
            raise ValueError("Agent response omitted requirement verification")
        evidence_ids = raw.get("evidenceIds")
        if not isinstance(evidence_ids, list):
            raise ValueError("Agent response omitted requirement evidence IDs")
        referenced.update(str(item) for item in evidence_ids)
        requirements.append(
            SourceRequirement.model_validate(
                {
                    "requirementId": raw.get("requirementId"),
                    "text": raw.get("text"),
                    "predictedStatus": raw.get("status"),
                    "evidenceIds": evidence_ids,
                    "verifierVersion": verification.get("verifierVersion"),
                }
            )
        )
    evidence = [
        SourceEvidence.model_validate(
            {
                "evidenceId": item.get("evidenceId"),
                "excerpt": item.get("excerpt"),
            }
        )
        for item in raw_evidence
        if isinstance(item, dict) and item.get("evidenceId") in referenced
    ]
    return SourceCase(
        id=case_id,
        tags=tags,
        requirements=requirements,
        evidence=evidence,
    )


def _cohens_kappa(labels_a: list[HumanLabel], labels_b: list[HumanLabel]) -> float:
    total = len(labels_a)
    observed = sum(a == b for a, b in zip(labels_a, labels_b, strict=True)) / total
    counts_a = Counter(labels_a)
    counts_b = Counter(labels_b)
    expected = sum(
        (counts_a[label] / total) * (counts_b[label] / total)
        for label in ("supported", "partial", "unsupported")
    )
    if expected == 1:
        return 1.0 if observed == 1 else 0.0
    return (observed - expected) / (1 - expected)


def _wilson_interval(positives: int, total: int) -> dict[str, float] | None:
    if total == 0:
        return None
    z = 1.959963984540054
    proportion = positives / total
    denominator = 1 + z**2 / total
    center = (proportion + z**2 / (2 * total)) / denominator
    margin = (
        z
        * (
            proportion * (1 - proportion) / total
            + z**2 / (4 * total**2)
        )
        ** 0.5
        / denominator
    )
    return {
        "lower": round(max(0.0, center - margin), 4),
        "upper": round(min(1.0, center + margin), 4),
    }


def evaluate_annotations(
    *,
    task_set: AnnotationTaskSet,
    prediction_key: PredictionKey,
    annotator_a: AnnotationSubmission,
    annotator_b: AnnotationSubmission,
    adjudication: AdjudicationSubmission | None,
) -> dict[str, Any]:
    expected_hash = task_set.task_set_sha256
    artifacts = (prediction_key, annotator_a, annotator_b)
    if any(artifact.task_set_sha256 != expected_hash for artifact in artifacts):
        raise ValueError("all annotation artifacts must target the same task set")
    if annotator_a.annotator_id == annotator_b.annotator_id:
        raise ValueError("two distinct annotators are required")
    if adjudication is not None:
        if adjudication.task_set_sha256 != expected_hash:
            raise ValueError("adjudication must target the same task set")
        if adjudication.adjudicator_id in {
            annotator_a.annotator_id,
            annotator_b.annotator_id,
        }:
            raise ValueError("the adjudicator must be independent of both annotators")

    pair_ids = [pair.pair_id for pair in task_set.pairs]
    expected_ids = set(pair_ids)
    keyed_ids = {item.pair_id for item in prediction_key.items}
    labels_a = {item.pair_id: item.label for item in annotator_a.labels}
    labels_b = {item.pair_id: item.label for item in annotator_b.labels}
    if keyed_ids != expected_ids or set(labels_a) != expected_ids or set(labels_b) != expected_ids:
        raise ValueError("prediction and annotation files must cover every pair exactly once")

    disagreements = {
        pair_id for pair_id in pair_ids if labels_a[pair_id] != labels_b[pair_id]
    }
    resolutions = (
        {item.pair_id: item.label for item in adjudication.resolutions}
        if adjudication is not None
        else {}
    )
    if set(resolutions) != disagreements:
        raise ValueError("adjudication must resolve exactly the annotator disagreements")
    gold = {
        pair_id: labels_a[pair_id]
        if labels_a[pair_id] == labels_b[pair_id]
        else resolutions[pair_id]
        for pair_id in pair_ids
    }

    predictions = {item.pair_id: item.predicted_status for item in prediction_key.items}
    labels_a_ordered = [labels_a[pair_id] for pair_id in pair_ids]
    labels_b_ordered = [labels_b[pair_id] for pair_id in pair_ids]
    accepted_ids = {
        pair_id for pair_id, status in predictions.items() if status != "not_found"
    }
    rejected_ids = expected_ids - accepted_ids
    unsupported_accepted = {
        pair_id for pair_id in accepted_ids if gold[pair_id] == "unsupported"
    }
    supported_rejected = {
        pair_id for pair_id in rejected_ids if gold[pair_id] != "unsupported"
    }
    confusion = {
        predicted: {
            label: sum(
                1
                for pair_id in pair_ids
                if predictions[pair_id] == predicted and gold[pair_id] == label
            )
            for label in ("supported", "partial", "unsupported")
        }
        for predicted in ("supported", "partial", "not_found")
    }
    verifier_versions = sorted({item.verifier_version for item in prediction_key.items})
    return {
        "schemaVersion": EVALUATION_RESULT_SCHEMA,
        "taskSetSha256": expected_hash,
        "datasetVersion": task_set.dataset_version,
        "datasetSha256": task_set.dataset_sha256,
        "verifierVersions": verifier_versions,
        "counts": {
            "pairs": len(pair_ids),
            "agreements": len(pair_ids) - len(disagreements),
            "disagreements": len(disagreements),
            "acceptedPredictions": len(accepted_ids),
            "rejectedPredictions": len(rejected_ids),
        },
        "annotation": {
            "agreementRate": round(1 - len(disagreements) / len(pair_ids), 4),
            "cohensKappa": round(_cohens_kappa(labels_a_ordered, labels_b_ordered), 4),
            "goldLabelDistribution": dict(sorted(Counter(gold.values()).items())),
        },
        "quality": {
            "unsupportedPositiveClaims": len(unsupported_accepted),
            "unsupportedPositiveClaimRate": round(
                len(unsupported_accepted) / len(accepted_ids), 4
            )
            if accepted_ids
            else None,
            "unsupportedPositiveClaimWilson95": _wilson_interval(
                len(unsupported_accepted), len(accepted_ids)
            ),
            "supportedOrPartialRejected": len(supported_rejected),
            "falseRejectionRate": round(
                len(supported_rejected) / len(rejected_ids), 4
            )
            if rejected_ids
            else None,
            "falseRejectionWilson95": _wilson_interval(
                len(supported_rejected), len(rejected_ids)
            ),
            "confusionMatrix": confusion,
        },
        "qualityGates": None,
    }


def evaluate_quality_gates(
    report: dict[str, Any],
    *,
    min_pairs: int,
    min_accepted_predictions: int,
    min_rejected_predictions: int,
    min_kappa: float,
    max_unsupported_positive_rate: float,
    max_false_rejection_rate: float,
) -> dict[str, Any]:
    counts = report["counts"]
    annotation = report["annotation"]
    quality = report["quality"]
    unsupported_rate = quality["unsupportedPositiveClaimRate"]
    false_rejection_rate = quality["falseRejectionRate"]
    checks = {
        "minimumPairs": counts["pairs"] >= min_pairs,
        "minimumAcceptedPredictions": (
            counts["acceptedPredictions"] >= min_accepted_predictions
        ),
        "minimumRejectedPredictions": (
            counts["rejectedPredictions"] >= min_rejected_predictions
        ),
        "annotatorAgreement": annotation["cohensKappa"] >= min_kappa,
        "unsupportedPositiveClaimRate": (
            unsupported_rate is not None
            and unsupported_rate <= max_unsupported_positive_rate
        ),
        "falseRejectionRate": (
            false_rejection_rate is not None
            and false_rejection_rate <= max_false_rejection_rate
        ),
    }
    return {
        "thresholds": {
            "minPairs": min_pairs,
            "minAcceptedPredictions": min_accepted_predictions,
            "minRejectedPredictions": min_rejected_predictions,
            "minCohensKappa": min_kappa,
            "maxUnsupportedPositiveClaimRate": max_unsupported_positive_rate,
            "maxFalseRejectionRate": max_false_rejection_rate,
        },
        "checks": checks,
        "passed": all(checks.values()),
    }
