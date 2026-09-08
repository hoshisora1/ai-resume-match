package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AnalysisDataDeletionService {
    private static final Logger log = LoggerFactory.getLogger(AnalysisDataDeletionService.class);
    private static final String TASK_AGGREGATE_TYPE = "analysis_task";

    private final AnalysisTaskRepository taskRepository;
    private final MatchReportRepository reportRepository;
    private final AnalysisSubmissionIdempotencyRepository idempotencyRepository;
    private final AnalysisOutboxRepository outboxRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final ReportCache reportCache;

    public AnalysisDataDeletionService(
        AnalysisTaskRepository taskRepository,
        MatchReportRepository reportRepository,
        AnalysisSubmissionIdempotencyRepository idempotencyRepository,
        AnalysisOutboxRepository outboxRepository,
        ResumeRepository resumeRepository,
        JobDescriptionRepository jobRepository,
        ReportCache reportCache
    ) {
        this.taskRepository = taskRepository;
        this.reportRepository = reportRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.outboxRepository = outboxRepository;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.reportCache = reportCache;
    }

    @Transactional
    public boolean deleteOwned(Long taskId, String ownerId) {
        return taskRepository.findOwnedForDeletion(taskId, ownerId)
            .map(task -> delete(task, "user"))
            .orElse(false);
    }

    @Transactional
    public boolean deleteExpired(Long taskId, LocalDateTime cutoff) {
        return taskRepository.findForDeletion(taskId)
            .filter(task -> !task.getCreatedAt().isAfter(cutoff))
            .map(task -> delete(task, "retention"))
            .orElse(false);
    }

    private boolean delete(AnalysisTask task, String reason) {
        Long taskId = task.getId();
        Long resumeId = task.getResumeId();
        Long jobId = task.getJobDescriptionId();

        reportRepository.deleteByTaskId(taskId);
        idempotencyRepository.deleteByTaskId(taskId);
        outboxRepository.deleteByAggregate(TASK_AGGREGATE_TYPE, taskId);
        taskRepository.delete(task);
        taskRepository.flush();

        if (!taskRepository.existsByResumeId(resumeId)) {
            resumeRepository.deleteOwnedById(resumeId, task.getOwnerId());
        }
        if (!taskRepository.existsByJobDescriptionId(jobId)) {
            jobRepository.deleteOwnedById(jobId, task.getOwnerId());
        }

        reportCache.evict(taskId);
        log.info("event=analysis_data_deleted taskId={} reason={}", taskId, reason);
        return true;
    }
}
