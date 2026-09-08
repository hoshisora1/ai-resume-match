package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.application.analysis.AgentProtocol.AgentAnalysisResponse;
import com.zhulikang.aimatch.application.analysis.AgentProtocol.GroundedClaim;
import com.zhulikang.aimatch.application.analysis.AgentProtocol.ModelUsage;
import com.zhulikang.aimatch.application.analysis.AgentProtocol.RequirementResult;
import com.zhulikang.aimatch.application.analysis.AgentProtocol.RunMetadata;
import com.zhulikang.aimatch.application.analysis.AgentProtocol.ScoreBreakdown;
import com.zhulikang.aimatch.application.analysis.AgentProtocol.StructuredEvidence;
import com.zhulikang.aimatch.application.analysis.AgentProtocol.StructuredReport;
import com.zhulikang.aimatch.application.analysis.AgentProtocol.ToolTrace;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates untrusted Agent reports once, before persistence. */
final class AgentReportValidator {
    private AgentReportValidator() {}

    static void validate(
        AgentAnalysisResponse body,
        StructuredReport report,
        AnalysisInput input
    ) {
        if (!AnalysisResult.STRUCTURED_SCHEMA_VERSION.equals(report.schemaVersion())) {
            throw new IllegalArgumentException("Agent service returned an unsupported report schema");
        }
        if (report.matchScore() == null || !report.matchScore().equals(body.matchScore())) {
            throw new IllegalArgumentException("Structured report score did not match the response");
        }
        if (report.scoreBreakdown() == null
            || report.scoreBreakdown().finalScore() == null
            || !report.scoreBreakdown().finalScore().equals(body.matchScore())) {
            throw new IllegalArgumentException("Structured score breakdown did not match the response");
        }
        if (report.requirements() == null || report.requirements().isEmpty()) {
            throw new IllegalArgumentException("Structured report did not contain requirements");
        }
        if (report.evidence() == null) {
            throw new IllegalArgumentException("Structured report did not contain an evidence collection");
        }
        requireNonBlank(body.model(), "model");
        requireNonBlank(body.promptVersion(), "prompt version");
        requireNonBlank(body.retrieverVersion(), "retriever version");
        requireNonBlank(body.verifierVersion(), "verifier version");
        if (body.modelUsage() == null || body.toolTrace() == null) {
            throw new IllegalArgumentException("Agent service response omitted provenance data");
        }
        validateProvenance(body, input);

        Set<String> evidenceIds = validateEvidence(report.evidence());
        Set<String> referencedIds = new HashSet<>();
        ScoreBreakdown expected = validateRequirements(report.requirements(), body.verifierVersion(), referencedIds);
        if (!expected.equals(report.scoreBreakdown())) {
            throw new IllegalArgumentException("Structured report score breakdown was inconsistent");
        }
        addClaimEvidence(report.coreClaims(), referencedIds);
        addClaimEvidence(report.matchedSkills(), referencedIds);
        if (!evidenceIds.equals(referencedIds)) {
            throw new IllegalArgumentException("Structured report evidence graph was inconsistent");
        }
    }

    private static Set<String> validateEvidence(List<StructuredEvidence> evidenceItems) {
        Set<String> evidenceIds = new HashSet<>();
        for (StructuredEvidence evidence : evidenceItems) {
            if (evidence == null || evidence.evidenceId() == null || evidence.evidenceId().isBlank()
                || !evidenceIds.add(evidence.evidenceId())) {
                throw new IllegalArgumentException("Structured report contained invalid evidence IDs");
            }
            if (evidence.excerpt() == null || evidence.excerpt().isBlank()) {
                throw new IllegalArgumentException("Structured report contained blank evidence");
            }
            if (evidence.score() == null || !Double.isFinite(evidence.score())
                || evidence.score() < -1 || evidence.score() > 1
                || (evidence.sourceStart() == null) != (evidence.sourceEnd() == null)
                || (evidence.sourceStart() != null
                    && (evidence.sourceStart() < 0 || evidence.sourceEnd() <= evidence.sourceStart()))) {
                throw new IllegalArgumentException("Structured report contained invalid evidence metadata");
            }
        }
        return evidenceIds;
    }

    private static ScoreBreakdown validateRequirements(
        List<RequirementResult> requirements,
        String verifierVersion,
        Set<String> referencedIds
    ) {
        Set<String> requirementIds = new HashSet<>();
        int totalWeight = 0;
        int supportedWeight = 0;
        int partialWeight = 0;
        boolean missingMustHave = false;
        for (RequirementResult requirement : requirements) {
            if (requirement == null || requirement.requirementId() == null
                || !requirement.requirementId().matches("^requirement:[0-9]+$")
                || !requirementIds.add(requirement.requirementId())
                || requirement.text() == null || requirement.text().isBlank()
                || requirement.explanation() == null || requirement.explanation().isBlank()) {
                throw new IllegalArgumentException("Structured report contained an invalid requirement");
            }
            if (requirement.weight() == null || requirement.weight() < 1 || requirement.weight() > 5
                || requirement.mustHave() == null) {
                throw new IllegalArgumentException("Structured report contained invalid requirement weights");
            }
            List<String> requirementEvidence = requirement.evidenceIds();
            if (requirementEvidence == null
                || new HashSet<>(requirementEvidence).size() != requirementEvidence.size()) {
                throw new IllegalArgumentException("Structured report contained invalid requirement evidence");
            }
            int finalRank = statusRank(requirement.status());
            if (finalRank > statusRank(requirement.modelStatus())
                || requirement.verification() == null
                || finalRank > statusRank(requirement.verification().status())) {
                throw new IllegalArgumentException("Structured report upgraded an unverified status");
            }
            if ((finalRank == 0) != requirementEvidence.isEmpty()) {
                throw new IllegalArgumentException("Structured report status and evidence disagreed");
            }
            List<String> verificationEvidence = requirement.verification().evidenceIds();
            if (!verifierVersion.equals(requirement.verification().verifierVersion())
                || requirement.verification().termCoverage() == null
                || !Double.isFinite(requirement.verification().termCoverage())
                || requirement.verification().termCoverage() < 0
                || requirement.verification().termCoverage() > 1
                || requirement.verification().reason() == null
                || requirement.verification().reason().isBlank()
                || verificationEvidence == null
                || new HashSet<>(verificationEvidence).size() != verificationEvidence.size()
                || !new HashSet<>(verificationEvidence).containsAll(requirementEvidence)
                || (statusRank(requirement.verification().status()) == 0)
                    != verificationEvidence.isEmpty()) {
                throw new IllegalArgumentException("Structured report used unverified evidence");
            }
            totalWeight += requirement.weight();
            if (finalRank == 2) {
                supportedWeight += requirement.weight();
            } else if (finalRank == 1) {
                partialWeight += requirement.weight();
            } else if (requirement.mustHave()) {
                missingMustHave = true;
            }
            referencedIds.addAll(requirementEvidence);
        }
        int missingWeight = totalWeight - supportedWeight - partialWeight;
        int rawScore = (int) Math.floor(
            ((supportedWeight + partialWeight * 0.5) * 100 / totalWeight) + 0.5
        );
        int expectedScore = missingMustHave ? Math.min(rawScore, 69) : rawScore;
        return new ScoreBreakdown(
            rawScore, expectedScore, totalWeight, supportedWeight, partialWeight, missingWeight,
            expectedScore < rawScore
        );
    }

    private static void validateProvenance(AgentAnalysisResponse body, AnalysisInput input) {
        ModelUsage usage = body.modelUsage();
        if (body.steps() < 1
            || usage.promptTokens() == null || usage.promptTokens() < 0
            || usage.completionTokens() == null || usage.completionTokens() < 0
            || usage.totalTokens() == null || usage.totalTokens() < 0
            || usage.providerReported() == null) {
            throw new IllegalArgumentException("Agent service response contained invalid provenance data");
        }
        if (body.traceId() != null && !body.traceId().matches("^[0-9a-f]{32}$")) {
            throw new IllegalArgumentException("Agent service response contained an invalid trace ID");
        }
        boolean hasEstimatedCost = usage.estimatedCostUsd() != null;
        boolean hasPricingVersion = usage.pricingVersion() != null;
        if (hasEstimatedCost != hasPricingVersion
            || (hasEstimatedCost && !Boolean.TRUE.equals(usage.providerReported()))
            || (hasEstimatedCost && (usage.estimatedCostUsd().length() > 64
                || !usage.estimatedCostUsd().matches("^(0|[1-9][0-9]*)(\\.[0-9]{1,8})?$")))
            || (hasPricingVersion && (usage.pricingVersion().isBlank()
                || usage.pricingVersion().length() > 120
                || usage.pricingVersion().chars().anyMatch(Character::isISOControl)))) {
            throw new IllegalArgumentException("Agent service response contained invalid cost provenance");
        }
        for (ToolTrace trace : body.toolTrace()) {
            if (trace == null || trace.name() == null || trace.name().isBlank()
                || trace.outcome() == null || trace.outcome().isBlank()
                || trace.durationMs() < 0 || trace.durationMs() > 3_600_000) {
                throw new IllegalArgumentException("Agent service response contained an invalid tool trace");
            }
        }
        validateRunMetadata(body, input);
    }

    private static void validateRunMetadata(AgentAnalysisResponse body, AnalysisInput input) {
        RunMetadata metadata = body.runMetadata();
        if (metadata == null) {
            return;
        }
        if (!AnalysisInputFingerprint.RUN_METADATA_SCHEMA_VERSION.equals(metadata.schemaVersion())
            || !AnalysisInputFingerprint.REQUEST_SCHEMA_VERSION.equals(metadata.requestSchemaVersion())
            || !AnalysisInputFingerprint.AGENT_RUNTIME_VERSION.equals(metadata.agentRuntimeVersion())
            || !AnalysisInputFingerprint.FINGERPRINT_VERSION.equals(metadata.inputFingerprintVersion())
            || metadata.inputFingerprint() == null
            || !metadata.inputFingerprint().matches("^[0-9a-f]{64}$")
            || !AnalysisInputFingerprint.create(input).equals(metadata.inputFingerprint())
            || metadata.chatProviderCalls() == null
            || metadata.chatProviderCalls() < 1
            || metadata.chatProviderCalls() > 12
            || metadata.chatProviderCalls() != body.steps()
            || !validDuration(metadata.chatProviderDurationMs())
            || !validDuration(metadata.toolDurationMs())
            || !validDuration(metadata.totalDurationMs())
            || metadata.chatProviderDurationMs() > metadata.totalDurationMs()
            || metadata.contextCharsSent() == null
            || metadata.contextCharsSent() < 0
            || metadata.contextCharsSent() > 100_000_000) {
            throw new IllegalArgumentException("Agent service response contained invalid run metadata");
        }
        long tracedToolDuration = 0;
        for (ToolTrace trace : body.toolTrace()) {
            if (tracedToolDuration > 3_600_000 - trace.durationMs()) {
                throw new IllegalArgumentException("Agent service response contained invalid run metadata");
            }
            tracedToolDuration += trace.durationMs();
        }
        if (tracedToolDuration != metadata.toolDurationMs()) {
            throw new IllegalArgumentException("Agent service response contained inconsistent run metadata");
        }
    }

    private static boolean validDuration(Long value) {
        return value != null && value >= 0 && value <= 3_600_000;
    }

    private static void addClaimEvidence(List<GroundedClaim> claims, Set<String> referencedIds) {
        if (claims == null) {
            throw new IllegalArgumentException("Structured report omitted a claim collection");
        }
        for (GroundedClaim claim : claims) {
            if (claim == null || claim.claim() == null || claim.claim().isBlank()
                || claim.evidenceIds() == null || claim.evidenceIds().isEmpty()
                || new HashSet<>(claim.evidenceIds()).size() != claim.evidenceIds().size()) {
                throw new IllegalArgumentException("Structured report contained an invalid claim");
            }
            referencedIds.addAll(claim.evidenceIds());
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Agent service response omitted " + field);
        }
    }

    private static int statusRank(String status) {
        return switch (status == null ? "" : status) {
            case "not_found" -> 0;
            case "partial" -> 1;
            case "supported" -> 2;
            default -> throw new IllegalArgumentException("Structured report contained an invalid status");
        };
    }

}
