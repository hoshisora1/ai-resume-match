package com.zhulikang.aimatch.analysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class AnalysisTaskService {
    private final AnalysisTaskRepository taskRepository;
    private final MatchReportRepository reportRepository;
    private final Duration runningTimeout;

    public AnalysisTaskService(
        AnalysisTaskRepository taskRepository,
        MatchReportRepository reportRepository,
        @Value("${analysis.running-timeout:15m}") Duration runningTimeout
    ) {
        this.taskRepository = taskRepository;
        this.reportRepository = reportRepository;
        this.runningTimeout = runningTimeout;
    }

    @Transactional
    public boolean tryStart(Long taskId, boolean redelivered) {
        LocalDateTime now = LocalDateTime.now();
        return taskRepository.markRunningIfPendingOrStale(
            taskId,
            AnalysisTask.Status.RUNNING,
            AnalysisTask.Status.PENDING,
            now.minus(runningTimeout),
            now,
            redelivered
        ) == 1;
    }

    @Transactional
    public void completeSuccess(MatchReport report) {
        int updated = taskRepository.markSuccess(
            report.getTaskId(),
            AnalysisTask.Status.SUCCESS,
            AnalysisTask.Status.RUNNING,
            LocalDateTime.now()
        );
        if (updated != 1) {
            throw new IllegalStateException("Only running analysis tasks can be completed");
        }
        reportRepository.save(report);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long taskId) {
        markFinalFailure(taskId, AnalysisFailureCode.UNEXPECTED_ERROR, "Analysis failed");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRetryableFailure(Long taskId, AnalysisFailureCode failureCode, String failureMessage) {
        int updated = taskRepository.markFailure(
            taskId,
            AnalysisTask.Status.FAILED_RETRYABLE,
            AnalysisTask.Status.RUNNING,
            failureCode,
            failureMessage,
            LocalDateTime.now()
        );
        if (updated != 1) {
            throw new IllegalStateException("Only running analysis tasks can be marked failed");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFinalFailure(Long taskId, AnalysisFailureCode failureCode, String failureMessage) {
        int updated = taskRepository.markFailure(
            taskId,
            AnalysisTask.Status.FAILED_FINAL,
            AnalysisTask.Status.RUNNING,
            failureCode,
            failureMessage,
            LocalDateTime.now()
        );
        if (updated != 1) {
            throw new IllegalStateException("Only running analysis tasks can be marked failed");
        }
    }
}
