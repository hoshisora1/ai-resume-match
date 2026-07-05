package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.analysis.AnalysisTask;

import java.time.LocalDateTime;

public record AnalysisTaskResponse(
    Long taskId,
    Long resumeId,
    Long jobDescriptionId,
    String status,
    int attemptCount,
    int maxAttempts,
    String failureCode,
    String failureMessage,
    LocalDateTime nextRetryAt,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static AnalysisTaskResponse from(AnalysisTask task) {
        return new AnalysisTaskResponse(
            task.getId(),
            task.getResumeId(),
            task.getJobDescriptionId(),
            task.getStatus().name(),
            task.getAttemptCount(),
            task.getMaxAttempts(),
            task.getFailureCode() == null ? null : task.getFailureCode().name(),
            task.getFailureMessage(),
            task.getNextRetryAt(),
            task.getStartedAt(),
            task.getCompletedAt(),
            task.getCreatedAt(),
            task.getUpdatedAt()
        );
    }
}
