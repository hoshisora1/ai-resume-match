package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.application.analysis.AnalysisListItem;

import java.time.LocalDateTime;

public record AnalysisListItemResponse(
    Long taskId,
    String jobTitle,
    String resumeFileName,
    String status,
    Integer matchScore,
    int attemptCount,
    int maxAttempts,
    String failureCode,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime completedAt
) {
    public static AnalysisListItemResponse from(AnalysisListItem item) {
        return new AnalysisListItemResponse(
            item.taskId(),
            item.jobTitle(),
            item.resumeFileName(),
            item.status().name(),
            item.matchScore(),
            item.attemptCount(),
            item.maxAttempts(),
            item.failureCode() == null ? null : item.failureCode().name(),
            item.createdAt(),
            item.updatedAt(),
            item.completedAt()
        );
    }
}
