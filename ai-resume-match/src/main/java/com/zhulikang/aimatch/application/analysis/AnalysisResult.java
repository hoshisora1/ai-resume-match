package com.zhulikang.aimatch.application.analysis;

public record AnalysisResult(
    int matchScore,
    String reportContent,
    String reportSchemaVersion,
    String structuredReportJson,
    String provenanceJson
) {
    public static final String MARKDOWN_SCHEMA_VERSION = "markdown-v1";
    public static final String STRUCTURED_SCHEMA_VERSION = "match-report-v2";

    public AnalysisResult(int matchScore, String reportContent) {
        this(matchScore, reportContent, MARKDOWN_SCHEMA_VERSION, null, null);
    }

    public AnalysisResult {
        if (matchScore < 0 || matchScore > 100) {
            throw new IllegalArgumentException("Match score must be between 0 and 100");
        }
        if (reportContent == null || reportContent.isBlank()) {
            throw new IllegalArgumentException("Analysis report must not be blank");
        }
        if (!MARKDOWN_SCHEMA_VERSION.equals(reportSchemaVersion)
            && !STRUCTURED_SCHEMA_VERSION.equals(reportSchemaVersion)) {
            throw new IllegalArgumentException("Unsupported report schema version");
        }
        if (STRUCTURED_SCHEMA_VERSION.equals(reportSchemaVersion)) {
            if (structuredReportJson == null || structuredReportJson.isBlank()) {
                throw new IllegalArgumentException("Structured report JSON must not be blank");
            }
            if (provenanceJson == null || provenanceJson.isBlank()) {
                throw new IllegalArgumentException("Analysis provenance JSON must not be blank");
            }
        }
    }
}
