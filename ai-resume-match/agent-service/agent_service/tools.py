from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from html import escape as html_escape
from typing import Any

from pydantic import ConfigDict, Field, ValidationError

from agent_service.models import AnalysisRequest, ApiModel, ReportSubmission, to_camel
from agent_service.retrieval import Evidence, HashingRetriever


class ToolArguments(ApiModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        serialize_by_alias=True,
        extra="forbid",
    )


class NoArguments(ToolArguments):
    pass


class SearchResumeArguments(ToolArguments):
    query: str = Field(min_length=2, max_length=240)
    top_k: int = Field(default=3, ge=1, le=5)


class SubmitReportArguments(ReportSubmission):
    pass


@dataclass(slots=True)
class ToolContext:
    request: AnalysisRequest
    retriever: HashingRetriever
    job_requirements_read: bool = False
    resume_searches: int = 0
    evidence: dict[str, Evidence] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class ToolExecution:
    output: dict[str, Any]
    outcome: str
    final_report: ReportSubmission | None = None


class ToolRegistry:
    GET_JOB_REQUIREMENTS = "get_job_requirements"
    SEARCH_RESUME_EVIDENCE = "search_resume_evidence"
    SUBMIT_MATCH_REPORT = "submit_match_report"

    def definitions(self) -> list[dict[str, Any]]:
        return [
            self._definition(
                self.GET_JOB_REQUIREMENTS,
                "Read the untrusted job description and normalized skill tags before searching the resume.",
                NoArguments.model_json_schema(),
            ),
            self._definition(
                self.SEARCH_RESUME_EVIDENCE,
                "Search the candidate resume for evidence relevant to one focused requirement. Call more than once when requirements differ. A successful call may return an empty evidence list when no sufficiently relevant resume chunk exists.",
                SearchResumeArguments.model_json_schema(),
            ),
            self._definition(
                self.SUBMIT_MATCH_REPORT,
                "Submit the final grounded report. Put every positive resume fact in coreClaims or matchedSkills and attach evidenceIds to that individual claim. Every id must come from this run's non-empty search_resume_evidence results. When searches find no evidence, submit empty positive-claim lists and a conservative score.",
                SubmitReportArguments.model_json_schema(),
            ),
        ]

    def execute(self, name: str, raw_arguments: str, context: ToolContext) -> ToolExecution:
        try:
            arguments = json.loads(raw_arguments or "{}")
            if not isinstance(arguments, dict):
                raise ValueError("arguments must be an object")
        except (json.JSONDecodeError, ValueError):
            return self._error("invalid_json_arguments")

        try:
            if name == self.GET_JOB_REQUIREMENTS:
                NoArguments.model_validate(arguments)
                context.job_requirements_read = True
                return ToolExecution(
                    output={
                        "ok": True,
                        "untrustedData": {
                            "jobTitle": context.request.job_title,
                            "jobDescription": context.request.job_description,
                            "skillTags": context.request.skill_tags,
                        },
                    },
                    outcome="success",
                )
            if name == self.SEARCH_RESUME_EVIDENCE:
                parsed = SearchResumeArguments.model_validate(arguments)
                if not context.job_requirements_read:
                    return self._error("read_job_requirements_first")
                results = context.retriever.search(parsed.query, parsed.top_k)
                context.resume_searches += 1
                context.evidence.update({item.evidence_id: item for item in results})
                return ToolExecution(
                    output={
                        "ok": True,
                        "untrustedData": {
                            "query": parsed.query,
                            "noEvidenceFound": not results,
                            "evidence": [
                                {
                                    "evidenceId": item.evidence_id,
                                    "excerpt": item.excerpt,
                                    "score": round(item.score, 4),
                                }
                                for item in results
                            ],
                        },
                    },
                    outcome="success",
                )
            if name == self.SUBMIT_MATCH_REPORT:
                parsed = SubmitReportArguments.model_validate(arguments)
                if not context.job_requirements_read:
                    return self._error("read_job_requirements_first")
                if context.resume_searches == 0:
                    return self._error("search_resume_evidence_first")
                positive_claims = [*parsed.core_claims, *parsed.matched_skills]
                cited_ids = {
                    evidence_id
                    for claim in positive_claims
                    for evidence_id in claim.evidence_ids
                }
                unknown = sorted(cited_ids - set(context.evidence))
                if unknown:
                    return self._error("unknown_evidence_ids", details=unknown[:5])
                if not positive_claims and parsed.match_score > 30:
                    return self._error("score_requires_grounded_claims")
                return ToolExecution(
                    output={
                        "ok": True,
                        "accepted": True,
                        "groundedClaimCount": len(positive_claims),
                        "citedEvidenceCount": len(cited_ids),
                    },
                    outcome="success",
                    final_report=ReportSubmission.model_validate(parsed.model_dump()),
                )
            return self._error("tool_not_allowed")
        except ValidationError as exc:
            fields = [".".join(str(part) for part in error["loc"]) for error in exc.errors()]
            return self._error("invalid_arguments", details=fields[:8])

    @staticmethod
    def render_report(report: ReportSubmission, evidence: dict[str, Evidence]) -> str:
        def safe(value: str) -> str:
            cleaned = re.sub(r"[\x00-\x1f\x7f]", " ", value).strip()
            cleaned = re.sub(r"\s+", " ", cleaned)
            escaped = html_escape(cleaned, quote=False)
            return re.sub(r"([\\`*_{}\[\]()#+.!|>~-])", r"\\\1", escaped)

        def bullets(values: list[str]) -> str:
            return "\n".join(f"- {safe(value)}" for value in values)

        def claim_bullets(values: list[Any], empty_message: str) -> str:
            if not values:
                return f"- {empty_message}"
            return "\n".join(
                f"- {safe(item.claim)} _(evidence: "
                + ", ".join(f"`{evidence_id}`" for evidence_id in item.evidence_ids)
                + ")_"
                for item in values
            )

        cited_ids = sorted(
            {
                evidence_id
                for claim in [*report.core_claims, *report.matched_skills]
                for evidence_id in claim.evidence_ids
            },
            key=lambda value: int(value.split(":", 1)[1]),
        )
        evidence_map = (
            "\n".join(
                f"- `{evidence_id}` | score={evidence[evidence_id].score:.4f} | "
                f"excerpt: {safe(evidence[evidence_id].excerpt)}"
                for evidence_id in cited_ids
            )
            if cited_ids
            else "- 无被引用的简历证据。"
        )

        return (
            f"匹配分数: {report.match_score}\n\n"
            "## 核心结论\n"
            f"{claim_bullets(report.core_claims, '未形成有证据支持的正向结论。')}\n\n"
            "## 已匹配技能\n"
            f"{claim_bullets(report.matched_skills, '未检索到可验证的匹配技能。')}\n\n"
            f"## 技能差距\n{bullets(report.skill_gaps)}\n\n"
            f"## 改进建议\n{bullets(report.recommendations)}\n\n"
            f"## 模拟面试题\n{bullets(report.interview_questions)}\n\n"
            f"## 证据引用与原文映射\n{evidence_map}"
        )

    @staticmethod
    def _definition(name: str, description: str, parameters: dict[str, Any]) -> dict[str, Any]:
        parameters.pop("title", None)
        return {
            "type": "function",
            "function": {
                "name": name,
                "description": description,
                "parameters": parameters,
                "strict": True,
            },
        }

    @staticmethod
    def _error(code: str, details: list[str] | None = None) -> ToolExecution:
        output: dict[str, Any] = {"ok": False, "error": code}
        if details:
            output["details"] = details
        return ToolExecution(output=output, outcome="denied")
