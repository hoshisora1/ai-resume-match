from __future__ import annotations

import re
from enum import StrEnum
from decimal import Decimal
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


def to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ApiModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        serialize_by_alias=True,
        extra="forbid",
    )


class AnalysisRequest(ApiModel):
    task_id: int = Field(gt=0)
    resume_text: str = Field(min_length=1, max_length=200_000)
    job_title: str = Field(min_length=1, max_length=120)
    job_description: str = Field(min_length=1, max_length=50_000)
    skill_tags: list[str] = Field(default_factory=list, max_length=80)
    correlation_id: str | None = Field(
        default=None,
        max_length=128,
        pattern=r"^[A-Za-z0-9._:-]+$",
    )

    @field_validator("resume_text", "job_title", "job_description")
    @classmethod
    def clean_untrusted_text(cls, value: str) -> str:
        cleaned = value.replace("\x00", "").strip()
        if not cleaned:
            raise ValueError("text must contain non-whitespace characters")
        return cleaned

    @field_validator("skill_tags")
    @classmethod
    def clean_skill_tags(cls, values: list[str]) -> list[str]:
        result: list[str] = []
        seen: set[str] = set()
        for value in values:
            cleaned = re.sub(r"[\x00-\x1f\x7f]", "", value).strip()[:80]
            key = cleaned.casefold()
            if cleaned and key not in seen:
                result.append(cleaned)
                seen.add(key)
        return result


class ModelToolCall(ApiModel):
    call_id: str = Field(min_length=1, max_length=200)
    name: str = Field(min_length=1, max_length=100)
    arguments: str = Field(default="{}", max_length=30_000)


class ModelUsage(ApiModel):
    prompt_tokens: int = Field(default=0, ge=0)
    completion_tokens: int = Field(default=0, ge=0)
    total_tokens: int = Field(default=0, ge=0)
    provider_reported: bool = False
    estimated_cost_usd: Decimal | None = Field(default=None, ge=0, decimal_places=8)
    pricing_version: str | None = Field(default=None, min_length=1, max_length=120)

    @model_validator(mode="after")
    def validate_cost_estimate(self) -> ModelUsage:
        if (self.estimated_cost_usd is None) != (self.pricing_version is None):
            raise ValueError("estimated cost and pricing version must be present together")
        if self.estimated_cost_usd is not None and not self.provider_reported:
            raise ValueError("estimated cost requires complete provider-reported usage")
        return self


class AssistantTurn(ApiModel):
    content: str | None = Field(default=None, max_length=20_000)
    tool_calls: list[ModelToolCall] = Field(default_factory=list, max_length=10)
    usage: ModelUsage = Field(default_factory=ModelUsage)

    def as_message(self) -> dict[str, Any]:
        message: dict[str, Any] = {"role": "assistant", "content": self.content}
        if self.tool_calls:
            message["tool_calls"] = [
                {
                    "id": call.call_id,
                    "type": "function",
                    "function": {
                        "name": call.name,
                        "arguments": call.arguments,
                    },
                }
                for call in self.tool_calls
            ]
        return message


class ToolTrace(ApiModel):
    name: str
    outcome: str
    duration_ms: int = Field(ge=0)


class RunMetadata(ApiModel):
    schema_version: Literal["agent-run-v1"] = "agent-run-v1"
    request_schema_version: Literal["agent-analysis-request-v1"] = (
        "agent-analysis-request-v1"
    )
    agent_runtime_version: Literal["bounded-tool-agent-v1"] = "bounded-tool-agent-v1"
    input_fingerprint_version: Literal["sha256-task-scoped-length-prefixed-v1"] = (
        "sha256-task-scoped-length-prefixed-v1"
    )
    input_fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")
    chat_provider_calls: int = Field(ge=1, le=12)
    chat_provider_duration_ms: int = Field(ge=0, le=3_600_000)
    tool_duration_ms: int = Field(ge=0, le=3_600_000)
    total_duration_ms: int = Field(ge=0, le=3_600_000)
    context_chars_sent: int = Field(ge=0, le=100_000_000)


ReportItem = Annotated[str, Field(min_length=1, max_length=500)]
EvidenceId = Annotated[str, Field(min_length=1, max_length=50, pattern=r"^resume:\d+$")]
RequirementId = Annotated[
    str,
    Field(min_length=1, max_length=50, pattern=r"^requirement:\d+$"),
]


class RequirementStatus(StrEnum):
    SUPPORTED = "supported"
    PARTIAL = "partial"
    NOT_FOUND = "not_found"


class JobRequirement(ApiModel):
    requirement_id: RequirementId
    text: ReportItem
    must_have: bool
    weight: int = Field(ge=1, le=5)


class RequirementAssessment(ApiModel):
    requirement_id: RequirementId
    status: RequirementStatus
    explanation: ReportItem
    evidence_ids: list[EvidenceId] = Field(max_length=5)

    @model_validator(mode="after")
    def validate_evidence_by_status(self) -> RequirementAssessment:
        if self.status is RequirementStatus.NOT_FOUND and self.evidence_ids:
            raise ValueError("not_found assessments must not cite evidence")
        if self.status is not RequirementStatus.NOT_FOUND and not self.evidence_ids:
            raise ValueError("supported and partial assessments must cite evidence")
        if len(self.evidence_ids) != len(set(self.evidence_ids)):
            raise ValueError("assessment evidence ids must be unique")
        return self


class EvidenceVerification(ApiModel):
    verifier_version: str = Field(min_length=1, max_length=120)
    status: RequirementStatus
    term_coverage: float = Field(ge=0, le=1)
    reason: str = Field(min_length=1, max_length=120)
    evidence_ids: list[EvidenceId] = Field(default_factory=list, max_length=5)

    @model_validator(mode="after")
    def validate_evidence_by_status(self) -> EvidenceVerification:
        if self.status is RequirementStatus.NOT_FOUND and self.evidence_ids:
            raise ValueError("not_found verification must not cite evidence")
        if self.status is not RequirementStatus.NOT_FOUND and not self.evidence_ids:
            raise ValueError("positive verification must cite evidence")
        if len(self.evidence_ids) != len(set(self.evidence_ids)):
            raise ValueError("verification evidence ids must be unique")
        return self


class RequirementResult(JobRequirement):
    model_status: RequirementStatus
    status: RequirementStatus
    explanation: ReportItem
    evidence_ids: list[EvidenceId] = Field(default_factory=list, max_length=5)
    verification: EvidenceVerification

    @model_validator(mode="after")
    def validate_final_decision(self) -> RequirementResult:
        rank = {
            RequirementStatus.NOT_FOUND: 0,
            RequirementStatus.PARTIAL: 1,
            RequirementStatus.SUPPORTED: 2,
        }
        if rank[self.status] > rank[self.model_status]:
            raise ValueError("final status must not upgrade the model proposal")
        if rank[self.status] > rank[self.verification.status]:
            raise ValueError("final status must not exceed verifier status")
        if self.status is RequirementStatus.NOT_FOUND and self.evidence_ids:
            raise ValueError("not_found result must not cite evidence")
        if self.status is not RequirementStatus.NOT_FOUND and not self.evidence_ids:
            raise ValueError("positive result must cite evidence")
        if not set(self.evidence_ids).issubset(self.verification.evidence_ids):
            raise ValueError("result evidence must be accepted by the verifier")
        return self


class GroundedClaim(ApiModel):
    claim: ReportItem
    evidence_ids: list[EvidenceId] = Field(min_length=1, max_length=5)

    @field_validator("claim")
    @classmethod
    def clean_claim(cls, value: str) -> str:
        cleaned = re.sub(r"[\x00-\x1f\x7f]", " ", value).strip()
        if not cleaned:
            raise ValueError("claim must not be blank")
        return cleaned

    @field_validator("evidence_ids")
    @classmethod
    def reject_duplicate_evidence_ids(cls, values: list[str]) -> list[str]:
        if len(values) != len(set(values)):
            raise ValueError("claim evidence ids must be unique")
        return values


class ReportSubmission(ApiModel):
    match_score: int = Field(ge=0, le=100)
    core_claims: list[GroundedClaim] = Field(max_length=12)
    matched_skills: list[GroundedClaim] = Field(max_length=20)
    skill_gaps: list[ReportItem] = Field(min_length=1, max_length=20)
    recommendations: list[ReportItem] = Field(min_length=3, max_length=5)
    interview_questions: list[ReportItem] = Field(min_length=3, max_length=8)

    @field_validator(
        "skill_gaps",
        "recommendations",
        "interview_questions",
    )
    @classmethod
    def clean_list(cls, values: list[str]) -> list[str]:
        cleaned = [re.sub(r"[\x00-\x1f\x7f]", " ", item).strip() for item in values]
        if any(not item for item in cleaned):
            raise ValueError("list items must not be blank")
        return cleaned

    @model_validator(mode="after")
    def validate_claim_set(self) -> ReportSubmission:
        claims = [*self.core_claims, *self.matched_skills]
        normalized = [item.claim.casefold() for item in claims]
        if len(normalized) != len(set(normalized)):
            raise ValueError("positive claims must be unique")
        evidence_ids = {evidence_id for item in claims for evidence_id in item.evidence_ids}
        if len(evidence_ids) > 12:
            raise ValueError("report may cite at most 12 distinct evidence ids")
        return self


class StructuredEvidence(ApiModel):
    evidence_id: EvidenceId
    excerpt: str = Field(min_length=1, max_length=900)
    score: float = Field(ge=-1, le=1)
    source_start: int | None = Field(default=None, ge=0)
    source_end: int | None = Field(default=None, ge=0)

    @field_validator("excerpt")
    @classmethod
    def clean_excerpt(cls, value: str) -> str:
        cleaned = re.sub(r"[\x00-\x1f\x7f]", " ", value).strip()
        cleaned = re.sub(r"\s+", " ", cleaned)
        if not cleaned:
            raise ValueError("evidence excerpt must not be blank")
        return cleaned

    @model_validator(mode="after")
    def validate_offsets(self) -> StructuredEvidence:
        if (self.source_start is None) != (self.source_end is None):
            raise ValueError("source offsets must both be present or absent")
        if self.source_start is not None and self.source_end <= self.source_start:
            raise ValueError("source end must be greater than source start")
        return self


class ScoreBreakdown(ApiModel):
    raw_score: int = Field(ge=0, le=100)
    final_score: int = Field(ge=0, le=100)
    total_weight: int = Field(ge=1)
    supported_weight: int = Field(ge=0)
    partial_weight: int = Field(ge=0)
    missing_weight: int = Field(ge=0)
    must_have_cap_applied: bool

    @model_validator(mode="after")
    def validate_weights_and_score(self) -> ScoreBreakdown:
        if self.supported_weight + self.partial_weight + self.missing_weight != self.total_weight:
            raise ValueError("score breakdown weights must sum to total weight")
        if self.final_score > self.raw_score:
            raise ValueError("final score must not exceed raw score")
        if self.must_have_cap_applied != (self.final_score < self.raw_score):
            raise ValueError("must-have cap flag must match the score reduction")
        return self


class StructuredMatchReport(ApiModel):
    schema_version: Literal["match-report-v2"] = "match-report-v2"
    match_score: int = Field(ge=0, le=100)
    requirements: list[RequirementResult] = Field(min_length=1, max_length=5)
    core_claims: list[GroundedClaim] = Field(max_length=12)
    matched_skills: list[GroundedClaim] = Field(max_length=20)
    skill_gaps: list[ReportItem] = Field(min_length=1, max_length=20)
    recommendations: list[ReportItem] = Field(min_length=3, max_length=5)
    interview_questions: list[ReportItem] = Field(min_length=3, max_length=8)
    evidence: list[StructuredEvidence] = Field(max_length=12)
    score_breakdown: ScoreBreakdown

    @model_validator(mode="after")
    def validate_report_graph(self) -> StructuredMatchReport:
        if self.match_score != self.score_breakdown.final_score:
            raise ValueError("report score must match score breakdown")
        evidence_ids = [item.evidence_id for item in self.evidence]
        if len(evidence_ids) != len(set(evidence_ids)):
            raise ValueError("structured evidence ids must be unique")
        available = set(evidence_ids)
        referenced = {
            evidence_id
            for requirement in self.requirements
            for evidence_id in requirement.evidence_ids
        }
        referenced.update(
            evidence_id
            for claim in [*self.core_claims, *self.matched_skills]
            for evidence_id in claim.evidence_ids
        )
        if not referenced.issubset(available):
            raise ValueError("structured report references unknown evidence")
        if available != referenced:
            raise ValueError("structured report must not persist unreferenced evidence")
        return self


class AnalysisResponse(ApiModel):
    task_id: int
    match_score: int = Field(ge=0, le=100)
    report_markdown: str = Field(min_length=1, max_length=30_000)
    structured_report: StructuredMatchReport
    steps: int = Field(ge=1)
    model: str
    prompt_version: str = Field(min_length=1, max_length=120)
    retriever_version: str = Field(min_length=1, max_length=120)
    verifier_version: str = Field(min_length=1, max_length=120)
    trace_id: str | None = Field(default=None, pattern=r"^[0-9a-f]{32}$")
    run_metadata: RunMetadata
    model_usage: ModelUsage
    tool_trace: list[ToolTrace]
    requirement_results: list[RequirementResult]

    @model_validator(mode="after")
    def validate_structured_report(self) -> AnalysisResponse:
        if self.match_score != self.structured_report.match_score:
            raise ValueError("top-level score must match structured report")
        if self.requirement_results != self.structured_report.requirements:
            raise ValueError("top-level requirements must match structured report")
        if self.run_metadata.chat_provider_calls != self.steps:
            raise ValueError("chat provider calls must match agent steps")
        if self.run_metadata.tool_duration_ms != sum(
            item.duration_ms for item in self.tool_trace
        ):
            raise ValueError("run metadata tool duration must match tool trace")
        if self.run_metadata.chat_provider_duration_ms > self.run_metadata.total_duration_ms:
            raise ValueError("chat provider duration must not exceed total duration")
        return self
