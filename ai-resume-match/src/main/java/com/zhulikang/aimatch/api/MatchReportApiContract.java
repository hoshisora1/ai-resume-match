package com.zhulikang.aimatch.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Explicit response-only wire contract for the versioned structured report.
 *
 * <p>The Agent boundary performs the semantic graph validation. These records
 * keep the public OpenAPI document and generated frontend types equally explicit
 * instead of degrading the payload to an unbounded JsonNode.</p>
 */
public final class MatchReportApiContract {
    private MatchReportApiContract() {
    }

    private static void requireAll(Object... values) {
        for (Object value : values) {
            Objects.requireNonNull(value, "Required match report contract field was null");
        }
    }

    @Schema(name = "StructuredMatchReport")
    public record StructuredMatchReport(
        @Schema(requiredMode = REQUIRED, allowableValues = "match-report-v2")
        String schemaVersion,
        @Schema(requiredMode = REQUIRED) @Min(0) @Max(100)
        int matchScore,
        @Schema(requiredMode = REQUIRED) @Size(min = 1, max = 5)
        List<RequirementResult> requirements,
        @Schema(requiredMode = REQUIRED) @Size(max = 12)
        List<GroundedClaim> coreClaims,
        @Schema(requiredMode = REQUIRED) @Size(max = 20)
        List<GroundedClaim> matchedSkills,
        @Schema(requiredMode = REQUIRED) @Size(min = 1, max = 20)
        List<@Size(min = 1, max = 500) String> skillGaps,
        @Schema(requiredMode = REQUIRED) @Size(min = 3, max = 5)
        List<@Size(min = 1, max = 500) String> recommendations,
        @Schema(requiredMode = REQUIRED) @Size(min = 3, max = 8)
        List<@Size(min = 1, max = 500) String> interviewQuestions,
        @Schema(requiredMode = REQUIRED) @Size(max = 12)
        List<StructuredEvidence> evidence,
        @Schema(requiredMode = REQUIRED)
        ScoreBreakdown scoreBreakdown
    ) {
        public StructuredMatchReport {
            requireAll(
                schemaVersion,
                requirements,
                coreClaims,
                matchedSkills,
                skillGaps,
                recommendations,
                interviewQuestions,
                evidence,
                scoreBreakdown
            );
        }
    }

    @Schema(name = "RequirementResult")
    public record RequirementResult(
        @Schema(requiredMode = REQUIRED) @Pattern(regexp = "^requirement:[0-9]+$")
        String requirementId,
        @Schema(requiredMode = REQUIRED) @Size(min = 1, max = 500)
        String text,
        @Schema(requiredMode = REQUIRED)
        boolean mustHave,
        @Schema(requiredMode = REQUIRED) @Min(1) @Max(5)
        int weight,
        @Schema(requiredMode = REQUIRED, allowableValues = {"supported", "partial", "not_found"})
        String modelStatus,
        @Schema(requiredMode = REQUIRED, allowableValues = {"supported", "partial", "not_found"})
        String status,
        @Schema(requiredMode = REQUIRED) @Size(min = 1, max = 500)
        String explanation,
        @Schema(requiredMode = REQUIRED) @Size(max = 5)
        List<@Pattern(regexp = "^resume:[0-9]+$") String> evidenceIds,
        @Schema(requiredMode = REQUIRED)
        EvidenceVerification verification
    ) {
        public RequirementResult {
            requireAll(
                requirementId,
                text,
                modelStatus,
                status,
                explanation,
                evidenceIds,
                verification
            );
        }
    }

    @Schema(name = "EvidenceVerification")
    public record EvidenceVerification(
        @Schema(requiredMode = REQUIRED) @Size(min = 1, max = 120)
        String verifierVersion,
        @Schema(requiredMode = REQUIRED, allowableValues = {"supported", "partial", "not_found"})
        String status,
        @Schema(requiredMode = REQUIRED) @DecimalMin("0") @DecimalMax("1")
        double termCoverage,
        @Schema(requiredMode = REQUIRED) @Size(min = 1, max = 120)
        String reason,
        @Schema(requiredMode = REQUIRED) @Size(max = 5)
        List<@Pattern(regexp = "^resume:[0-9]+$") String> evidenceIds
    ) {
        public EvidenceVerification {
            requireAll(verifierVersion, status, reason, evidenceIds);
        }
    }

    @Schema(name = "GroundedClaim")
    public record GroundedClaim(
        @Schema(requiredMode = REQUIRED) @Size(min = 1, max = 500)
        String claim,
        @Schema(requiredMode = REQUIRED) @Size(min = 1, max = 5)
        List<@Pattern(regexp = "^resume:[0-9]+$") String> evidenceIds
    ) {
        public GroundedClaim {
            requireAll(claim, evidenceIds);
        }
    }

    @Schema(name = "StructuredEvidence")
    public record StructuredEvidence(
        @Schema(requiredMode = REQUIRED) @Pattern(regexp = "^resume:[0-9]+$")
        String evidenceId,
        @Schema(requiredMode = REQUIRED) @Size(min = 1, max = 900)
        String excerpt,
        @Schema(requiredMode = REQUIRED) @DecimalMin("-1") @DecimalMax("1")
        double score,
        @Schema(requiredMode = REQUIRED, nullable = true, minimum = "0")
        Integer sourceStart,
        @Schema(requiredMode = REQUIRED, nullable = true, minimum = "1")
        Integer sourceEnd
    ) {
        public StructuredEvidence {
            requireAll(evidenceId, excerpt);
        }
    }

    @Schema(name = "ScoreBreakdown")
    public record ScoreBreakdown(
        @Schema(requiredMode = REQUIRED) @Min(0) @Max(100)
        int rawScore,
        @Schema(requiredMode = REQUIRED) @Min(0) @Max(100)
        int finalScore,
        @Schema(requiredMode = REQUIRED) @Min(1)
        int totalWeight,
        @Schema(requiredMode = REQUIRED) @Min(0)
        int supportedWeight,
        @Schema(requiredMode = REQUIRED) @Min(0)
        int partialWeight,
        @Schema(requiredMode = REQUIRED) @Min(0)
        int missingWeight,
        @Schema(requiredMode = REQUIRED)
        boolean mustHaveCapApplied
    ) {
    }

    @Schema(name = "AnalysisProvenance")
    public record AnalysisProvenance(
        @Schema(requiredMode = REQUIRED, allowableValues = "analysis-run-v1")
        String schemaVersion,
        @Schema(requiredMode = REQUIRED) @Size(min = 1, max = 128)
        String correlationId,
        @Schema(requiredMode = REQUIRED, nullable = true, pattern = "^[0-9a-f]{32}$")
        String traceId,
        @Schema(requiredMode = REQUIRED) @Size(min = 1)
        String model,
        @Schema(requiredMode = REQUIRED) @Size(min = 1, max = 120)
        String promptVersion,
        @Schema(requiredMode = REQUIRED) @Size(min = 1, max = 120)
        String retrieverVersion,
        @Schema(requiredMode = REQUIRED) @Size(min = 1, max = 120)
        String verifierVersion,
        @Schema(requiredMode = REQUIRED, minimum = "1")
        int steps,
        @Schema(requiredMode = REQUIRED, nullable = true)
        RunMetadata runMetadata,
        @Schema(requiredMode = REQUIRED)
        ModelUsage modelUsage,
        @Schema(requiredMode = REQUIRED)
        List<ToolTrace> toolTrace
    ) {
        public AnalysisProvenance {
            requireAll(
                schemaVersion,
                correlationId,
                model,
                promptVersion,
                retrieverVersion,
                verifierVersion,
                modelUsage,
                toolTrace
            );
        }
    }

    @Schema(name = "RunMetadata")
    public record RunMetadata(
        @Schema(requiredMode = REQUIRED, allowableValues = "agent-run-v1")
        String schemaVersion,
        @Schema(requiredMode = REQUIRED, allowableValues = "agent-analysis-request-v1")
        String requestSchemaVersion,
        @Schema(requiredMode = REQUIRED, allowableValues = "bounded-tool-agent-v1")
        String agentRuntimeVersion,
        @Schema(requiredMode = REQUIRED, allowableValues = "sha256-task-scoped-length-prefixed-v1")
        String inputFingerprintVersion,
        @Schema(requiredMode = REQUIRED, pattern = "^[0-9a-f]{64}$")
        String inputFingerprint,
        @Schema(requiredMode = REQUIRED, minimum = "1", maximum = "12")
        int chatProviderCalls,
        @Schema(requiredMode = REQUIRED, minimum = "0", maximum = "3600000")
        long chatProviderDurationMs,
        @Schema(requiredMode = REQUIRED, minimum = "0", maximum = "3600000")
        long toolDurationMs,
        @Schema(requiredMode = REQUIRED, minimum = "0", maximum = "3600000")
        long totalDurationMs,
        @Schema(requiredMode = REQUIRED, minimum = "0", maximum = "100000000")
        long contextCharsSent
    ) {
        public RunMetadata {
            requireAll(
                schemaVersion,
                requestSchemaVersion,
                agentRuntimeVersion,
                inputFingerprintVersion,
                inputFingerprint
            );
        }
    }

    @Schema(name = "ModelUsage")
    public record ModelUsage(
        @Schema(requiredMode = REQUIRED, minimum = "0")
        int promptTokens,
        @Schema(requiredMode = REQUIRED, minimum = "0")
        int completionTokens,
        @Schema(requiredMode = REQUIRED, minimum = "0")
        int totalTokens,
        @Schema(requiredMode = REQUIRED)
        boolean providerReported,
        @Schema(
            requiredMode = REQUIRED,
            nullable = true,
            pattern = "^(0|[1-9][0-9]*)(\\.[0-9]{1,8})?$"
        )
        String estimatedCostUsd,
        @Schema(requiredMode = REQUIRED, nullable = true, minLength = 1, maxLength = 120)
        String pricingVersion
    ) {
    }

    @Schema(name = "ToolTrace")
    public record ToolTrace(
        @Schema(requiredMode = REQUIRED, minLength = 1)
        String name,
        @Schema(requiredMode = REQUIRED, minLength = 1)
        String outcome,
        @Schema(requiredMode = REQUIRED, minimum = "0", maximum = "3600000")
        long durationMs
    ) {
        public ToolTrace {
            requireAll(name, outcome);
        }
    }
}
