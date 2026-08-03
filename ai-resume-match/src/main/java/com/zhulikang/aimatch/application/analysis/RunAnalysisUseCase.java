package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisFailureCode;
import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.analysis.AnalysisTaskService;
import com.zhulikang.aimatch.analysis.MatchReport;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import com.zhulikang.aimatch.observability.RequestCorrelation;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.OptionalInt;

@Service
public class RunAnalysisUseCase {
    private static final Logger log = LoggerFactory.getLogger(RunAnalysisUseCase.class);

    private final AnalysisTaskService taskService;
    private final AnalysisTaskRepository taskRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final AnalysisEngine analysisEngine;
    private final AnalysisMetrics metrics;

    public RunAnalysisUseCase(
        AnalysisTaskService taskService,
        AnalysisTaskRepository taskRepository,
        ResumeRepository resumeRepository,
        JobDescriptionRepository jobRepository,
        AnalysisEngine analysisEngine,
        AnalysisMetrics metrics
    ) {
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.analysisEngine = analysisEngine;
        this.metrics = metrics;
    }

    public void run(Long taskId, boolean redelivered) {
        String correlationId = RequestCorrelation.currentCorrelationIdOrNew();
        OptionalInt claimedAttempt = taskService.tryStart(taskId, redelivered);
        if (claimedAttempt.isEmpty()) {
            log.info("event=analysis_task_skipped taskId={} redelivered={} reason=not_claimable", taskId, redelivered);
            return;
        }
        int expectedAttempt = claimedAttempt.getAsInt();
        Timer.Sample sample = metrics.startTimer();
        log.info(
            "event=analysis_task_started taskId={} attempt={} redelivered={} correlationId={}",
            taskId,
            expectedAttempt,
            redelivered,
            correlationId
        );
        try {
            AnalysisTask task = taskRepository.findById(taskId)
                .orElseThrow(SourceDataMissingException::new);
            Resume resume = resumeRepository.findById(task.getResumeId())
                .orElseThrow(SourceDataMissingException::new);
            JobDescription job = jobRepository.findById(task.getJobDescriptionId())
                .orElseThrow(SourceDataMissingException::new);

            AnalysisResult result = analysisEngine.analyze(new AnalysisInput(
                task.getId(),
                resume.getRawText(),
                job.getTitle(),
                job.getContent(),
                Arrays.stream(job.getSkillTags().split(",")).filter(tag -> !tag.isBlank()).toList(),
                correlationId
            ));
            boolean completed = taskService.completeSuccess(
                new MatchReport(task.getId(), result.matchScore(), result.reportContent()),
                expectedAttempt
            );
            if (!completed) {
                logStaleLease(taskId, expectedAttempt, "success", null, correlationId, sample);
                return;
            }
            metrics.taskSucceeded(sample);
            log.info(
                "event=analysis_task_succeeded taskId={} resumeId={} jobDescriptionId={} attempt={}",
                task.getId(),
                task.getResumeId(),
                task.getJobDescriptionId(),
                expectedAttempt
            );
        } catch (SourceDataMissingException ex) {
            boolean marked = taskService.markFinalFailure(
                taskId,
                expectedAttempt,
                AnalysisFailureCode.SOURCE_DATA_MISSING
            );
            if (!marked) {
                logStaleLease(
                    taskId,
                    expectedAttempt,
                    "failure",
                    AnalysisFailureCode.SOURCE_DATA_MISSING,
                    correlationId,
                    sample
                );
                return;
            }
            metrics.taskFailed(AnalysisFailureCode.SOURCE_DATA_MISSING, sample);
            logFailure(taskId, expectedAttempt, AnalysisFailureCode.SOURCE_DATA_MISSING, false, ex, correlationId);
        } catch (IllegalArgumentException ex) {
            boolean marked = taskService.markFinalFailure(
                taskId,
                expectedAttempt,
                AnalysisFailureCode.REPORT_PARSE_FAILED
            );
            if (!marked) {
                logStaleLease(
                    taskId,
                    expectedAttempt,
                    "failure",
                    AnalysisFailureCode.REPORT_PARSE_FAILED,
                    correlationId,
                    sample
                );
                return;
            }
            metrics.taskFailed(AnalysisFailureCode.REPORT_PARSE_FAILED, sample);
            logFailure(taskId, expectedAttempt, AnalysisFailureCode.REPORT_PARSE_FAILED, false, ex, correlationId);
        } catch (RuntimeException ex) {
            boolean marked = taskService.markRetryableFailure(
                taskId,
                expectedAttempt,
                AnalysisFailureCode.AI_UNAVAILABLE
            );
            if (!marked) {
                logStaleLease(
                    taskId,
                    expectedAttempt,
                    "failure",
                    AnalysisFailureCode.AI_UNAVAILABLE,
                    correlationId,
                    sample
                );
                return;
            }
            metrics.taskFailed(AnalysisFailureCode.AI_UNAVAILABLE, sample);
            logFailure(taskId, expectedAttempt, AnalysisFailureCode.AI_UNAVAILABLE, true, ex, correlationId);
        }
    }

    private void logFailure(
        Long taskId,
        int expectedAttempt,
        AnalysisFailureCode failureCode,
        boolean retryable,
        RuntimeException exception,
        String correlationId
    ) {
        log.warn(
            "event=analysis_task_failed taskId={} attempt={} failureCode={} retryable={} exceptionType={} correlationId={}",
            taskId,
            expectedAttempt,
            failureCode,
            retryable,
            exception.getClass().getName(),
            correlationId
        );
    }

    private void logStaleLease(
        Long taskId,
        int expectedAttempt,
        String completion,
        AnalysisFailureCode failureCode,
        String correlationId,
        Timer.Sample sample
    ) {
        metrics.taskLeaseLost(sample);
        log.warn(
            "event=analysis_task_stale_lease taskId={} expectedAttempt={} completion={} failureCode={} correlationId={}",
            taskId,
            expectedAttempt,
            completion,
            failureCode == null ? "none" : failureCode,
            correlationId
        );
    }

    private static final class SourceDataMissingException extends RuntimeException {
    }
}
