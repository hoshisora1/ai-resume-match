package com.zhulikang.aimatch.analysis;

import java.time.LocalDateTime;

public record MatchReportView(
    Long taskId,
    int matchScore,
    String reportContent,
    String reportSchemaVersion,
    String structuredReportJson,
    String provenanceJson,
    LocalDateTime createdAt
) {
    public MatchReportView(Long taskId, int matchScore, String reportContent, LocalDateTime createdAt) {
        this(taskId, matchScore, reportContent, "markdown-v1", null, null, createdAt);
    }

    public MatchReportView {
        if (reportSchemaVersion == null || reportSchemaVersion.isBlank()) {
            reportSchemaVersion = "markdown-v1";
        }
    }

    public static MatchReportView from(MatchReport report) {
        return new MatchReportView(
            report.getTaskId(),
            report.getMatchScore(),
            report.getReportContent(),
            report.getReportSchemaVersion(),
            report.getStructuredReportJson(),
            report.getProvenanceJson(),
            report.getCreatedAt()
        );
    }
}
