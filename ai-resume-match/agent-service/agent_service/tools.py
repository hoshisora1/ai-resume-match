from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass, field
from html import escape as html_escape
from typing import Any

from pydantic import Field, ValidationError, field_validator

from agent_service.models import (
    AnalysisRequest,
    ApiModel,
    EvidenceVerification,
    GroundedClaim,
    JobRequirement,
    ReportItem,
    ReportSubmission,
    RequirementAssessment,
    RequirementId,
    RequirementResult,
    RequirementStatus,
    ScoreBreakdown,
    StructuredEvidence,
    StructuredMatchReport,
)
from agent_service.requirements import calculate_match_score, extract_requirements
from agent_service.retrieval import Evidence, Retriever
from agent_service.verification import (
    ConservativeLexicalEvidenceVerifier,
    EvidenceVerifier,
    fail_closed_decision,
)


class NoArguments(ApiModel):
    pass


class SearchResumeArguments(ApiModel):
    requirement_id: RequirementId
    query: str = Field(min_length=2, max_length=240)
    top_k: int = Field(ge=1, le=5)


class SubmitReportArguments(ApiModel):
    requirement_assessments: list[RequirementAssessment] = Field(min_length=1, max_length=5)
    recommendations: list[ReportItem] = Field(min_length=3, max_length=5)
    interview_questions: list[ReportItem] = Field(min_length=3, max_length=8)

    @field_validator("recommendations", "interview_questions")
    @classmethod
    def clean_list(cls, values: list[str]) -> list[str]:
        cleaned = [re.sub(r"[\x00-\x1f\x7f]", " ", item).strip() for item in values]
        if any(not item for item in cleaned):
            raise ValueError("list items must not be blank")
        return cleaned


@dataclass(slots=True)
class ToolContext:
    request: AnalysisRequest
    retriever: Retriever
    requirements: list[JobRequirement] = field(default_factory=list)
    job_requirements_read: bool = False
    resume_searches: int = 0
    evidence: dict[str, Evidence] = field(default_factory=dict)
    searched_requirements: set[str] = field(default_factory=set)
    requirement_evidence: dict[str, set[str]] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class ToolExecution:
    output: dict[str, Any]
    outcome: str
    final_report: ReportSubmission | None = None
    final_requirement_results: list[RequirementResult] | None = None


class ToolRegistry:
    GET_JOB_REQUIREMENTS = "get_job_requirements"
    SEARCH_RESUME_EVIDENCE = "search_resume_evidence"
    SUBMIT_MATCH_REPORT = "submit_match_report"

    def __init__(self, verifier: EvidenceVerifier | None = None) -> None:
        self._verifier = verifier or ConservativeLexicalEvidenceVerifier()

    @property
    def verifier_version(self) -> str:
        return self._verifier.version

    def definitions(self) -> list[dict[str, Any]]:
        return [
            self._definition(
                self.GET_JOB_REQUIREMENTS,
                "Read the untrusted job description and normalized skill tags before searching the resume.",
                NoArguments.model_json_schema(),
            ),
            self._definition(
                self.SEARCH_RESUME_EVIDENCE,
                "Search the candidate resume for evidence relevant to exactly one extracted requirementId. Search every requirement before submitting. Evidence retrieved for one requirement cannot support a different requirement. A successful call may return an empty evidence list.",
                SearchResumeArguments.model_json_schema(),
            ),
            self._definition(
                self.SUBMIT_MATCH_REPORT,
                "Propose one supported, partial, or not_found assessment for every extracted requirement. supported/partial must cite evidence IDs retrieved for that same requirement; not_found must cite none. A separate conservative verifier may downgrade the proposal before the runtime calculates the deterministic score.",
                SubmitReportArguments.model_json_schema(),
            ),
        ]

    def execute(self, name: str, raw_arguments: str, context: ToolContext) -> ToolExecution:
        try:
            arguments = json.loads(raw_arguments)
            if not isinstance(arguments, dict):
                raise ValueError("arguments must be an object")
        except ValueError:
            return self._error("invalid_json_arguments")

        try:
            if name == self.GET_JOB_REQUIREMENTS:
                NoArguments.model_validate(arguments)
                context.requirements = extract_requirements(
                    job_title=context.request.job_title,
                    job_description=context.request.job_description,
                    skill_tags=context.request.skill_tags,
                )
                context.job_requirements_read = True
                return ToolExecution(
                    output={
                        "ok": True,
                        "untrustedData": {
                            "jobTitle": context.request.job_title,
                            "jobDescription": context.request.job_description,
                            "skillTags": context.request.skill_tags,
                            "requirements": [
                                requirement.model_dump(by_alias=True)
                                for requirement in context.requirements
                            ],
                        },
                    },
                    outcome="success",
                )
            if name == self.SEARCH_RESUME_EVIDENCE:
                if not context.job_requirements_read:
                    return self._error("read_job_requirements_first")
                parsed = SearchResumeArguments.model_validate(arguments)
                requirement_ids = {
                    requirement.requirement_id for requirement in context.requirements
                }
                if parsed.requirement_id not in requirement_ids:
                    return self._error(
                        "unknown_requirement_id",
                        details=[parsed.requirement_id],
                    )
                results = context.retriever.search(parsed.query, parsed.top_k)
                context.resume_searches += 1
                context.searched_requirements.add(parsed.requirement_id)
                context.evidence.update({item.evidence_id: item for item in results})
                context.requirement_evidence.setdefault(parsed.requirement_id, set()).update(
                    item.evidence_id for item in results
                )
                return ToolExecution(
                    output={
                        "ok": True,
                        "untrustedData": {
                            "query": parsed.query,
                            "requirementId": parsed.requirement_id,
                            "noEvidenceFound": not results,
                            "evidence": [
                                {
                                    "evidenceId": item.evidence_id,
                                    "excerpt": item.excerpt,
                                    "score": round(item.score, 4),
                                    "sourceStart": item.source_start,
                                    "sourceEnd": item.source_end,
                                }
                                for item in results
                            ],
                        },
                    },
                    outcome="success",
                )
            if name == self.SUBMIT_MATCH_REPORT:
                if not context.job_requirements_read:
                    return self._error("read_job_requirements_first")
                parsed = SubmitReportArguments.model_validate(arguments)
                if context.resume_searches == 0:
                    return self._error("search_resume_evidence_first")
                expected_ids = {
                    requirement.requirement_id for requirement in context.requirements
                }
                assessment_ids = [
                    assessment.requirement_id
                    for assessment in parsed.requirement_assessments
                ]
                if len(assessment_ids) != len(set(assessment_ids)):
                    return self._error("duplicate_requirement_assessments")
                if set(assessment_ids) != expected_ids:
                    return self._error(
                        "requirement_assessments_incomplete",
                        details=sorted(expected_ids.symmetric_difference(assessment_ids))[:8],
                    )
                unsearched = sorted(expected_ids - context.searched_requirements)
                if unsearched:
                    return self._error("requirements_not_searched", details=unsearched[:8])

                for assessment in parsed.requirement_assessments:
                    allowed = context.requirement_evidence.get(
                        assessment.requirement_id,
                        set(),
                    )
                    invalid = sorted(set(assessment.evidence_ids) - allowed)
                    if invalid:
                        return self._error(
                            "evidence_not_retrieved_for_requirement",
                            details=invalid[:5],
                        )

                assessment_by_id = {
                    assessment.requirement_id: assessment
                    for assessment in parsed.requirement_assessments
                }
                status_rank = {
                    RequirementStatus.NOT_FOUND: 0,
                    RequirementStatus.PARTIAL: 1,
                    RequirementStatus.SUPPORTED: 2,
                }
                explanation_by_reason = {
                    "no_evidence": "未检索到可验证证据。",
                    "no_verifiable_terms": "该要求缺少可供规则验证的关键术语。",
                    "negated_evidence_only": "检索片段仅包含否定或缺失表述。",
                    "no_term_support": "引用片段未覆盖该要求的关键术语。",
                    "mixed_positive_and_negated_evidence": "引用片段同时包含正向与否定表述，仅保守判为部分支持。",
                    "required_terms_supported": "引用片段覆盖该要求的关键术语，且未命中否定模式。",
                    "partial_term_support": "引用片段仅覆盖部分关键术语。",
                    "verifier_error": "证据验证器执行失败，已按无支持证据处理。",
                }
                requirement_results: list[RequirementResult] = []
                for requirement in context.requirements:
                    assessment = assessment_by_id[requirement.requirement_id]
                    cited_evidence = [
                        context.evidence[evidence_id]
                        for evidence_id in assessment.evidence_ids
                    ]
                    try:
                        decision = self._verifier.verify(requirement, cited_evidence)
                    except Exception:
                        decision = fail_closed_decision()
                    final_status = min(
                        (assessment.status, decision.status),
                        key=status_rank.__getitem__,
                    )
                    final_evidence_ids = (
                        decision.evidence_ids
                        if final_status is not RequirementStatus.NOT_FOUND
                        else []
                    )
                    explanation = explanation_by_reason.get(
                        decision.reason,
                        "证据验证未达到完整支持要求。",
                    )
                    verification = EvidenceVerification(
                        verifierVersion=self._verifier.version,
                        status=decision.status,
                        termCoverage=decision.term_coverage,
                        reason=decision.reason,
                        evidenceIds=decision.evidence_ids,
                    )
                    requirement_results.append(
                        RequirementResult(
                            **requirement.model_dump(),
                            modelStatus=assessment.status,
                            status=final_status,
                            explanation=explanation,
                            evidenceIds=final_evidence_ids,
                            verification=verification,
                        )
                    )
                score = calculate_match_score(context.requirements, requirement_results)
                core_claims = [
                    GroundedClaim(
                        claim=f"满足要求「{result.text}」：{result.explanation}",
                        evidenceIds=result.evidence_ids,
                    )
                    for result in requirement_results
                    if result.status is RequirementStatus.SUPPORTED
                ]
                matched_skills = [
                    GroundedClaim(
                        claim=f"部分满足「{result.text}」：{result.explanation}",
                        evidenceIds=result.evidence_ids,
                    )
                    for result in requirement_results
                    if result.status is RequirementStatus.PARTIAL
                ]
                skill_gaps = [
                    (
                        f"部分证据：{result.text}"
                        if result.status is RequirementStatus.PARTIAL
                        else f"未找到证据：{result.text}"
                    )
                    for result in requirement_results
                    if result.status is not RequirementStatus.SUPPORTED
                ] or ["未发现基于当前岗位要求的证据缺口。"]
                final_report = ReportSubmission(
                    matchScore=score,
                    coreClaims=core_claims,
                    matchedSkills=matched_skills,
                    skillGaps=skill_gaps,
                    recommendations=parsed.recommendations,
                    interviewQuestions=parsed.interview_questions,
                )
                cited_ids = {
                    evidence_id
                    for result in requirement_results
                    for evidence_id in result.evidence_ids
                }
                return ToolExecution(
                    output={
                        "ok": True,
                        "accepted": True,
                        "computedMatchScore": score,
                        "verifierVersion": self._verifier.version,
                        "verifierDowngradeCount": sum(
                            result.status is not result.model_status
                            for result in requirement_results
                        ),
                        "requirementCount": len(requirement_results),
                        "groundedClaimCount": len(core_claims) + len(matched_skills),
                        "citedEvidenceCount": len(cited_ids),
                    },
                    outcome="success",
                    final_report=final_report,
                    final_requirement_results=requirement_results,
                )
            return self._error("tool_not_allowed")
        except ValidationError as exc:
            fields = [".".join(str(part) for part in error["loc"]) for error in exc.errors()]
            return self._error("invalid_arguments", details=fields[:8])

    @staticmethod
    def build_structured_report(
        report: ReportSubmission,
        evidence: dict[str, Evidence],
        requirement_results: list[RequirementResult],
    ) -> StructuredMatchReport:
        total_weight = sum(result.weight for result in requirement_results)
        supported_weight = sum(
            result.weight
            for result in requirement_results
            if result.status is RequirementStatus.SUPPORTED
        )
        partial_weight = sum(
            result.weight
            for result in requirement_results
            if result.status is RequirementStatus.PARTIAL
        )
        missing_weight = total_weight - supported_weight - partial_weight
        raw_score = math.floor(
            ((supported_weight + partial_weight * 0.5) * 100 / total_weight) + 0.5
        )
        cited_ids = sorted(
            {
                evidence_id
                for result in requirement_results
                for evidence_id in result.evidence_ids
            },
            key=lambda value: int(value.split(":", 1)[1]),
        )
        return StructuredMatchReport(
            matchScore=report.match_score,
            requirements=requirement_results,
            coreClaims=report.core_claims,
            matchedSkills=report.matched_skills,
            skillGaps=report.skill_gaps,
            recommendations=report.recommendations,
            interviewQuestions=report.interview_questions,
            evidence=[
                StructuredEvidence(
                    evidenceId=evidence_id,
                    excerpt=evidence[evidence_id].excerpt,
                    score=evidence[evidence_id].score,
                    sourceStart=evidence[evidence_id].source_start,
                    sourceEnd=evidence[evidence_id].source_end,
                )
                for evidence_id in cited_ids
            ],
            scoreBreakdown=ScoreBreakdown(
                rawScore=raw_score,
                finalScore=report.match_score,
                totalWeight=total_weight,
                supportedWeight=supported_weight,
                partialWeight=partial_weight,
                missingWeight=missing_weight,
                mustHaveCapApplied=report.match_score < raw_score,
            ),
        )

    @staticmethod
    def render_report(
        report: ReportSubmission,
        evidence: dict[str, Evidence],
        requirement_results: list[RequirementResult] | None = None,
    ) -> str:
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
        status_labels = {
            RequirementStatus.SUPPORTED: "支持",
            RequirementStatus.PARTIAL: "部分支持",
            RequirementStatus.NOT_FOUND: "未找到证据",
        }
        requirement_breakdown = "\n".join(
            f"- `{safe(result.requirement_id)}` | {status_labels[result.status]} | "
            f"weight={result.weight} | coverage={result.verification.term_coverage:.2f} | "
            f"{safe(result.text)}"
            + (
                " | evidence: "
                + ", ".join(f"`{evidence_id}`" for evidence_id in result.evidence_ids)
                if result.evidence_ids
                else ""
            )
            for result in (requirement_results or [])
        ) or "- 当前响应未包含结构化 requirement 结果。"

        return (
            f"匹配分数: {report.match_score}\n\n"
            "## 岗位要求逐项判定\n"
            f"{requirement_breakdown}\n\n"
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
