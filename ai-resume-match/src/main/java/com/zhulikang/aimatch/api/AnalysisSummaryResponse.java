package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.application.analysis.AnalysisSummary;

import java.math.BigDecimal;

public record AnalysisSummaryResponse(
    long totalCount,
    long successCount,
    long inProgressCount,
    long retryableFailureCount,
    BigDecimal averageMatchScore
) {
    public static AnalysisSummaryResponse from(AnalysisSummary summary) {
        return new AnalysisSummaryResponse(
            summary.totalCount(),
            summary.successCount(),
            summary.inProgressCount(),
            summary.retryableFailureCount(),
            summary.averageMatchScore()
        );
    }
}
