package com.zhulikang.aimatch.analysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final Duration retryDelay;

    @Autowired
    public AnalysisTaskService(
        AnalysisTaskRepository taskRepository,
        MatchReportRepository reportRepository,
        @Value("${analysis.running-timeout:15m}") Duration runningTimeout,
        @Value("${analysis.retry.delay:1m}") Duration retryDelay
    ) {
        this.taskRepository = taskRepository;
        this.reportRepository = reportRepository;
        this.runningTimeout = runningTimeout;
        this.retryDelay = retryDelay;
    }

    AnalysisTaskService(
        AnalysisTaskRepository taskRepository,
        MatchReportRepository reportRepository,
        Duration runningTimeout
    ) {
        this(taskRepository, reportRepository, runningTimeout, Duration.ofMinutes(1));
    }

    @Transactional
    public boolean tryStart(Long taskId, boolean redelivered) {
        LocalDateTime now = LocalDateTime.now();
        return taskRepository.markRunningIfPendingOrStale(
            taskId,
            AnalysisTask.Status.RUNNING,
            AnalysisTask.Status.PENDING,
            now.minus(runningTimeout),
            now
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
        AnalysisTask task = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalStateException("Analysis task not found"));
        LocalDateTime now = LocalDateTime.now();
        boolean attemptsExhausted = task.getAttemptCount() >= task.getMaxAttempts();
        int updated = taskRepository.markFailure(
            taskId,
            attemptsExhausted ? AnalysisTask.Status.FAILED_FINAL : AnalysisTask.Status.FAILED_RETRYABLE,
            AnalysisTask.Status.RUNNING,
            failureCode,
            failureMessage,
            attemptsExhausted ? null : now.plus(retryDelay),
            now
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
            null,
            LocalDateTime.now()
        );
        if (updated != 1) {
            throw new IllegalStateException("Only running analysis tasks can be marked failed");
        }
    }
}
