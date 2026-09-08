package com.zhulikang.aimatch.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhulikang.aimatch.analysis.MatchReportView;
import com.zhulikang.aimatch.api.MatchReportApiContract.AnalysisProvenance;
import com.zhulikang.aimatch.api.MatchReportApiContract.StructuredMatchReport;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.IOException;
import java.time.LocalDateTime;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record MatchReportResponse(
    @Schema(requiredMode = REQUIRED)
    Long taskId,
    @Schema(requiredMode = REQUIRED, minimum = "0", maximum = "100")
    int matchScore,
    @Schema(requiredMode = REQUIRED)
    String reportContent,
    @Schema(requiredMode = REQUIRED, allowableValues = {"markdown-v1", "match-report-v2"})
    String reportSchemaVersion,
    @Schema(requiredMode = REQUIRED, nullable = true)
    StructuredMatchReport structuredReport,
    @Schema(requiredMode = REQUIRED, nullable = true)
    AnalysisProvenance provenance,
    @Schema(requiredMode = REQUIRED)
    LocalDateTime createdAt
) {
    public static MatchReportResponse from(MatchReportView view, ObjectMapper objectMapper) {
        return new MatchReportResponse(
            view.taskId(),
            view.matchScore(),
            view.reportContent(),
            view.reportSchemaVersion(),
            parseObject(
                view.structuredReportJson(),
                "structured report",
                StructuredMatchReport.class,
                objectMapper
            ),
            parseObject(
                view.provenanceJson(),
                "analysis provenance",
                AnalysisProvenance.class,
                objectMapper
            ),
            view.createdAt()
        );
    }

    private static <T> T parseObject(
        String value,
        String label,
        Class<T> type,
        ObjectMapper objectMapper
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            if (!(node instanceof ObjectNode objectNode)) {
                throw new IllegalStateException("Stored " + label + " was not a JSON object");
            }
            addRollingCompatibilityDefaults(objectNode, type);
            return objectMapper.readerFor(type)
                .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .readValue(objectNode);
        } catch (IOException ex) {
            throw new IllegalStateException("Stored " + label + " was invalid JSON", ex);
        }
    }

    private static void addRollingCompatibilityDefaults(ObjectNode node, Class<?> type) {
        if (type != AnalysisProvenance.class) {
            return;
        }
        putNullIfMissing(node, "traceId");
        putNullIfMissing(node, "runMetadata");
        JsonNode usage = node.get("modelUsage");
        if (usage instanceof ObjectNode usageObject) {
            putNullIfMissing(usageObject, "estimatedCostUsd");
            putNullIfMissing(usageObject, "pricingVersion");
        }
    }

    private static void putNullIfMissing(ObjectNode node, String field) {
        if (!node.has(field)) {
            node.putNull(field);
        }
    }
}
