package com.zhulikang.aimatch.application.analysis;

import java.util.List;

/** Wire types for the internal Agent HTTP boundary. */
final class AgentProtocol {
    private AgentProtocol() {}

    record AgentAnalysisRequest(
        Long taskId,
        String resumeText,
        String jobTitle,
        String jobDescription,
        List<String> skillTags,
        String correlationId
    ) {
    }

    record AgentAnalysisResponse(
        Long taskId,
        Integer matchScore,
        String reportMarkdown,
        int steps,
        String model,
        String promptVersion,
        String retrieverVersion,
        String verifierVersion,
        String traceId,
        RunMetadata runMetadata,
        ModelUsage modelUsage,
        StructuredReport structuredReport,
        List<ToolTrace> toolTrace
    ) {
    }

    record ToolTrace(String name, String outcome, long durationMs) {
    }

    record RunMetadata(
        String schemaVersion,
        String requestSchemaVersion,
        String agentRuntimeVersion,
        String inputFingerprintVersion,
        String inputFingerprint,
        Integer chatProviderCalls,
        Long chatProviderDurationMs,
        Long toolDurationMs,
        Long totalDurationMs,
        Long contextCharsSent
    ) {
    }

    record ModelUsage(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Boolean providerReported,
        String estimatedCostUsd,
        String pricingVersion
    ) {
    }

    record StructuredReport(
        String schemaVersion,
        Integer matchScore,
        List<RequirementResult> requirements,
        List<GroundedClaim> coreClaims,
        List<GroundedClaim> matchedSkills,
        List<String> skillGaps,
        List<String> recommendations,
        List<String> interviewQuestions,
        List<StructuredEvidence> evidence,
        ScoreBreakdown scoreBreakdown
    ) {
    }

    record RequirementResult(
        String requirementId,
        String text,
        Boolean mustHave,
        Integer weight,
        String modelStatus,
        String status,
        String explanation,
        List<String> evidenceIds,
        EvidenceVerification verification
    ) {
    }

    record EvidenceVerification(
        String verifierVersion,
        String status,
        Double termCoverage,
        String reason,
        List<String> evidenceIds
    ) {
    }

    record GroundedClaim(String claim, List<String> evidenceIds) {
    }

    record StructuredEvidence(
        String evidenceId,
        String excerpt,
        Double score,
        Integer sourceStart,
        Integer sourceEnd
    ) {
    }

    record ScoreBreakdown(
        Integer rawScore,
        Integer finalScore,
        Integer totalWeight,
        Integer supportedWeight,
        Integer partialWeight,
        Integer missingWeight,
        Boolean mustHaveCapApplied
    ) {
    }

    record AnalysisProvenance(
        String schemaVersion,
        String correlationId,
        String traceId,
        String model,
        String promptVersion,
        String retrieverVersion,
        String verifierVersion,
        int steps,
        RunMetadata runMetadata,
        ModelUsage modelUsage,
        List<ToolTrace> toolTrace
    ) {
    }

    record AgentErrorResponse(
        String code,
        String message,
        Boolean retryable,
        Integer retryAfterSeconds
    ) {
    }

}
