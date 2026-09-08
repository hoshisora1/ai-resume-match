from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Protocol

from agent_service.models import JobRequirement, RequirementStatus
from agent_service.retrieval import Evidence, lexical_tokens, literal_term_pattern


RULE_VERIFIER_VERSION = "conservative-lexical-negation-v2"
IGNORED_TERMS = {
    "ability",
    "development",
    "engineer",
    "engineering",
    "experience",
    "knowledge",
    "platform",
    "skill",
    "skills",
    "能力",
    "开发",
    "工程",
    "平台",
    "技能",
    "熟悉",
    "经验",
    "要求",
    "负责",
}
ENGLISH_NEGATED_PREFIX = re.compile(
    r"(?:"
    r"\b(?:did|does|do|has|have|had)\s+not\s+"
    r"(?:use[sd]?|using|implement(?:ed|ing)?|deploy(?:ed|ing)?|"
    r"integrat(?:e|ed|ing)|work(?:ed|ing)?\s+with|have\s+experience\s+with)\s*|"
    r"\bnever\s+(?:used|implemented|deployed|integrated|worked\s+with)\s*|"
    r"\bwithout\s*|"
    r"\black(?:s|ed|ing)?\s*|"
    r"\bno\s+(?:(?:production|professional|hands-on)\s+)?"
    r"(?:(?:experience|knowledge|exposure|use|implementation)\s+(?:with|of|in)\s+)?|"
    r"\bnot\s+"
    r")$",
    re.IGNORECASE,
)
CHINESE_NEGATED_PREFIX = re.compile(
    r"(?:不具备|尚未|并未|没有|未曾|从未|未|无)"
    r"(?:实际|真实|生产|相关|任何|直接)*"
    r"(?:使用|接入|采用|部署|实现|具备|拥有|经验|经历|实践)?$"
)
CHINESE_NEGATED_SUFFIX = re.compile(
    r"^(?:不具备|尚未|并未|没有|未曾|从未|未|无)"
    r"(?:使用|接入|采用|部署|实现|具备|经验|经历)?"
)


@dataclass(frozen=True, slots=True)
class VerificationDecision:
    status: RequirementStatus
    term_coverage: float
    reason: str
    evidence_ids: list[str]


class EvidenceVerifier(Protocol):
    @property
    def version(self) -> str: ...

    def verify(
        self,
        requirement: JobRequirement,
        evidence: list[Evidence],
    ) -> VerificationDecision: ...


def _meaningful_terms(text: str) -> list[str]:
    return list(
        dict.fromkeys(term for term in lexical_tokens(text) if term not in IGNORED_TERMS)
    )


def _occurrence_is_negated(text: str, start: int, end: int) -> bool:
    prefix = text[max(0, start - 80) : start]
    suffix = text[end : min(len(text), end + 30)]
    english_prefix = ENGLISH_NEGATED_PREFIX.search(prefix)
    english_suffix = re.match(
        r"\W*(?:is|was|were|has|have|had)?\s*(?:not|never|lacking)\b",
        suffix,
        re.IGNORECASE,
    )
    compact_prefix = re.sub(r"\s+", "", prefix[-24:])
    compact_suffix = re.sub(r"\s+", "", suffix[:16])
    chinese_prefix = CHINESE_NEGATED_PREFIX.search(compact_prefix)
    chinese_suffix = CHINESE_NEGATED_SUFFIX.match(compact_suffix)
    return bool(english_prefix or english_suffix or chinese_prefix or chinese_suffix)


def _term_state(text: str, term: str) -> tuple[bool, bool]:
    folded = text.casefold()
    positive = False
    negated = False
    for match in literal_term_pattern(term).finditer(folded):
        if _occurrence_is_negated(folded, match.start(), match.end()):
            negated = True
        else:
            positive = True
    return positive, negated


class ConservativeLexicalEvidenceVerifier:
    @property
    def version(self) -> str:
        return RULE_VERIFIER_VERSION

    def verify(
        self,
        requirement: JobRequirement,
        evidence: list[Evidence],
    ) -> VerificationDecision:
        if not evidence:
            return VerificationDecision(
                RequirementStatus.NOT_FOUND,
                0.0,
                "no_evidence",
                [],
            )
        terms = _meaningful_terms(requirement.text)
        if not terms:
            return VerificationDecision(
                RequirementStatus.NOT_FOUND,
                0.0,
                "no_verifiable_terms",
                [],
            )

        matched_terms: set[str] = set()
        negated_terms: set[str] = set()
        accepted_ids: list[str] = []
        for item in evidence:
            phrase = requirement.text.casefold().strip()
            phrase_positive, phrase_negated = _term_state(item.excerpt, phrase)
            if phrase_negated and not phrase_positive:
                negated_terms.update(terms)
                continue
            evidence_has_positive = False
            for term in terms:
                positive, negated = _term_state(item.excerpt, term)
                if positive:
                    matched_terms.add(term)
                    evidence_has_positive = True
                if negated:
                    negated_terms.add(term)
            if evidence_has_positive and item.evidence_id not in accepted_ids:
                accepted_ids.append(item.evidence_id)

        coverage = len(matched_terms) / len(terms)
        if not matched_terms:
            reason = "negated_evidence_only" if negated_terms else "no_term_support"
            return VerificationDecision(
                RequirementStatus.NOT_FOUND,
                0.0,
                reason,
                [],
            )
        if negated_terms:
            status, reason = RequirementStatus.PARTIAL, "mixed_positive_and_negated_evidence"
        elif coverage >= 0.8:
            status, reason = RequirementStatus.SUPPORTED, "required_terms_supported"
        else:
            status, reason = RequirementStatus.PARTIAL, "partial_term_support"
        return VerificationDecision(status, round(coverage, 4), reason, accepted_ids)


def fail_closed_decision() -> VerificationDecision:
    return VerificationDecision(
        RequirementStatus.NOT_FOUND,
        0.0,
        "verifier_error",
        [],
    )
