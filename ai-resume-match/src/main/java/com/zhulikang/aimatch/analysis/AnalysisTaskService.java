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
        reportRepository.save(report);
        taskRepository.updateStatus(report.getTaskId(), AnalysisTask.Status.SUCCESS, LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long taskId) {
        taskRepository.updateStatus(taskId, AnalysisTask.Status.FAILED, LocalDateTime.now());
    }
}
