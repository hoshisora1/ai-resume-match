package com.zhulikang.aimatch.analysis;

import java.time.LocalDateTime;

public record MatchReportView(
    Long taskId,
    int matchScore,
    String reportContent,
    LocalDateTime createdAt
) {
    public static MatchReportView from(MatchReport report) {
        return new MatchReportView(
            report.getTaskId(),
            report.getMatchScore(),
            report.getReportContent(),
            report.getCreatedAt()
        );
    }
}
