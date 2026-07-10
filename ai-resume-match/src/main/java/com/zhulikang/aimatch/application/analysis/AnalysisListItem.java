package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisFailureCode;
import com.zhulikang.aimatch.analysis.AnalysisTask;

import java.time.LocalDateTime;

public record AnalysisListItem(
    Long taskId,
    String jobTitle,
    String resumeFileName,
    AnalysisTask.Status status,
    Integer matchScore,
    int attemptCount,
    int maxAttempts,
    AnalysisFailureCode failureCode,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime completedAt
) {
}
