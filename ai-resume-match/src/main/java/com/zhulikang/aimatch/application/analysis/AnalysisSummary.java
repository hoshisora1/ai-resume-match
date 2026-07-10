package com.zhulikang.aimatch.application.analysis;

import java.math.BigDecimal;

public record AnalysisSummary(
    long totalCount,
    long successCount,
    long inProgressCount,
    long retryableFailureCount,
    BigDecimal averageMatchScore
) {
}
