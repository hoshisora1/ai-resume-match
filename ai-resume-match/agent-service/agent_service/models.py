from __future__ import annotations

import re
from typing import Annotated, Any

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


class AnalysisResponse(ApiModel):
    task_id: int
    match_score: int = Field(ge=0, le=100)
    report_markdown: str = Field(min_length=1, max_length=30_000)
    steps: int = Field(ge=1)
    model: str
    model_usage: ModelUsage
    tool_trace: list[ToolTrace]


ReportItem = Annotated[str, Field(min_length=1, max_length=500)]
EvidenceId = Annotated[str, Field(min_length=1, max_length=50, pattern=r"^resume:\d+$")]


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
    core_claims: list[GroundedClaim] = Field(max_length=5)
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
