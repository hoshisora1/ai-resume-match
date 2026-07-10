package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.application.analysis.AnalysisSubmission;
import com.zhulikang.aimatch.application.analysis.AnalysisTaskDetails;

import java.time.LocalDateTime;

public record AnalysisTaskResponse(
    Long taskId,
    Long resumeId,
    Long jobDescriptionId,
    String jobTitle,
    String resumeFileName,
    Integer matchScore,
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
            null,
            null,
            null,
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

    public static AnalysisTaskResponse from(AnalysisTaskDetails details) {
        AnalysisTask task = details.task();
        return new AnalysisTaskResponse(
            task.getId(),
            task.getResumeId(),
            task.getJobDescriptionId(),
            details.jobTitle(),
            details.resumeFileName(),
            details.matchScore(),
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

    public static AnalysisTaskResponse from(AnalysisSubmission submission) {
        AnalysisTask task = submission.task();
        return new AnalysisTaskResponse(
            task.getId(),
            task.getResumeId(),
            task.getJobDescriptionId(),
            submission.jobTitle(),
            submission.resumeFileName(),
            null,
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
