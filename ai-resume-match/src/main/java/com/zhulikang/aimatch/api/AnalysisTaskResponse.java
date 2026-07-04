package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.analysis.AnalysisTask;

import java.time.LocalDateTime;

public record AnalysisTaskResponse(
    Long taskId,
    Long resumeId,
    Long jobDescriptionId,
    String status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static AnalysisTaskResponse from(AnalysisTask task) {
        return new AnalysisTaskResponse(
            task.getId(),
            task.getResumeId(),
            task.getJobDescriptionId(),
            task.getStatus().name(),
            task.getCreatedAt(),
            task.getUpdatedAt()
        );
    }
}
