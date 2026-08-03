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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class RunAnalysisUseCaseTest {
    private final AnalysisTaskService taskService = mock(AnalysisTaskService.class);
    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private final ResumeRepository resumeRepository = mock(ResumeRepository.class);
    private final JobDescriptionRepository jobRepository = mock(JobDescriptionRepository.class);
    private final AnalysisEngine analysisEngine = mock(AnalysisEngine.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void createsReportAndMarksTaskSuccess(CapturedOutput output) {
        AnalysisTask task = task(99L);
        when(taskService.tryStart(99L, false)).thenReturn(OptionalInt.of(1));
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(new Resume(
            "resume.docx",
            "Java Redis Kafka MySQL",
            "summary"
        )));
        when(jobRepository.findById(2L)).thenReturn(Optional.of(new JobDescription(
            "Backend Engineer",
            "Java backend, Redis and Kafka",
            "Java,Redis,Kafka"
        )));
        when(analysisEngine.analyze(any())).thenReturn(new AnalysisResult(88, "report with Redis Kafka"));
        when(taskService.completeSuccess(any(), eq(1))).thenReturn(true);

        useCase().run(99L, false);

        ArgumentCaptor<MatchReport> reportCaptor = ArgumentCaptor.forClass(MatchReport.class);
        verify(taskService).completeSuccess(reportCaptor.capture(), eq(1));
        assertThat(reportCaptor.getValue().getTaskId()).isEqualTo(99L);
        assertThat(reportCaptor.getValue().getMatchScore()).isEqualTo(88);
        assertThat(reportCaptor.getValue().getReportContent()).contains("Redis Kafka");
        ArgumentCaptor<AnalysisInput> inputCaptor = ArgumentCaptor.forClass(AnalysisInput.class);
        verify(analysisEngine).analyze(inputCaptor.capture());
        assertThat(inputCaptor.getValue().taskId()).isEqualTo(99L);
        assertThat(inputCaptor.getValue().skillTags()).containsExactly("Java", "Redis", "Kafka");
        assertThat(meterRegistry.counter("analysis.tasks.succeeded").count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("analysis.worker.duration")
            .tag("outcome", "success")
            .tag("failureCode", "none")
            .timer()).isNotNull();
        assertThat(output).contains("event=analysis_task_started taskId=99 attempt=1 redelivered=false")
            .contains("event=analysis_task_succeeded taskId=99");
    }

    @Test
    void marksRetryableFailureWithoutPersistingOrLoggingProviderDetails(CapturedOutput output) {
        AnalysisTask task = task(99L);
        when(taskService.tryStart(99L, false)).thenReturn(OptionalInt.of(1));
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(new Resume("resume.docx", "Java Redis", "summary")));
        when(jobRepository.findById(2L)).thenReturn(Optional.of(new JobDescription("Redis Engineer", "Redis", "Redis")));
        when(analysisEngine.analyze(any())).thenThrow(new IllegalStateException(
            "provider body: secret-upstream-payload"
        ));
        when(taskService.markRetryableFailure(99L, 1, AnalysisFailureCode.AI_UNAVAILABLE)).thenReturn(true);

        RequestCorrelation.put("retry-request", "retry-correlation");
        try {
            useCase().run(99L, false);
        } finally {
            RequestCorrelation.clear();
        }

        verify(taskService).markRetryableFailure(99L, 1, AnalysisFailureCode.AI_UNAVAILABLE);
        verify(taskService, never()).completeSuccess(any(), anyInt());
        assertThat(output)
            .contains("failureCode=AI_UNAVAILABLE")
            .contains("exceptionType=java.lang.IllegalStateException")
            .contains("correlationId=retry-correlation")
            .doesNotContain("secret-upstream-payload")
            .doesNotContain("provider body");
        assertThat(meterRegistry.counter(
            "analysis.tasks.failed",
            "failureCode",
            AnalysisFailureCode.AI_UNAVAILABLE.name()
        ).count()).isEqualTo(1.0);
    }

    @Test
    void treatsEngineNoSuchElementAsRetryableProviderFailure(CapturedOutput output) {
        AnalysisTask task = task(99L);
        when(taskService.tryStart(99L, false)).thenReturn(OptionalInt.of(1));
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(new Resume("resume.docx", "Java", "summary")));
        when(jobRepository.findById(2L)).thenReturn(Optional.of(new JobDescription("Engineer", "Java", "Java")));
        when(analysisEngine.analyze(any())).thenThrow(new NoSuchElementException(
            "provider returned empty choices: private-response"
        ));
        when(taskService.markRetryableFailure(99L, 1, AnalysisFailureCode.AI_UNAVAILABLE)).thenReturn(true);

        useCase().run(99L, false);

        verify(taskService).markRetryableFailure(99L, 1, AnalysisFailureCode.AI_UNAVAILABLE);
        verify(taskService, never()).markFinalFailure(99L, 1, AnalysisFailureCode.SOURCE_DATA_MISSING);
        assertThat(output)
            .contains("failureCode=AI_UNAVAILABLE")
            .contains("exceptionType=java.util.NoSuchElementException")
            .doesNotContain("private-response")
            .doesNotContain("empty choices");
    }

    @Test
    void marksFinalFailureWhenSourceDataIsMissing() {
        when(taskService.tryStart(99L, false)).thenReturn(OptionalInt.of(1));
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());
        when(taskService.markFinalFailure(99L, 1, AnalysisFailureCode.SOURCE_DATA_MISSING)).thenReturn(true);

        useCase().run(99L, false);

        verify(taskService).markFinalFailure(99L, 1, AnalysisFailureCode.SOURCE_DATA_MISSING);
        verify(analysisEngine, never()).analyze(any());
        assertThat(meterRegistry.counter(
            "analysis.tasks.failed",
            "failureCode",
            AnalysisFailureCode.SOURCE_DATA_MISSING.name()
        ).count()).isEqualTo(1.0);
    }

    @Test
    void marksFinalFailureWithoutPersistingOrLoggingInvalidReportDetails(CapturedOutput output) {
        AnalysisTask task = task(99L);
        when(taskService.tryStart(99L, false)).thenReturn(OptionalInt.of(1));
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(new Resume("resume.docx", "Java Redis", "summary")));
        when(jobRepository.findById(2L)).thenReturn(Optional.of(new JobDescription("Redis Engineer", "Redis", "Redis")));
        when(analysisEngine.analyze(any()))
            .thenThrow(new IllegalArgumentException("invalid report: private-model-output"));
        when(taskService.markFinalFailure(99L, 1, AnalysisFailureCode.REPORT_PARSE_FAILED)).thenReturn(true);

        RequestCorrelation.put("report-request", "report-correlation");
        try {
            useCase().run(99L, false);
        } finally {
            RequestCorrelation.clear();
        }

        verify(taskService).markFinalFailure(99L, 1, AnalysisFailureCode.REPORT_PARSE_FAILED);
        assertThat(output)
            .contains("failureCode=REPORT_PARSE_FAILED")
            .contains("exceptionType=java.lang.IllegalArgumentException")
            .contains("correlationId=report-correlation")
            .doesNotContain("private-model-output")
            .doesNotContain("invalid report");
        assertThat(meterRegistry.counter(
            "analysis.tasks.failed",
            "failureCode",
            AnalysisFailureCode.REPORT_PARSE_FAILED.name()
        ).count()).isEqualTo(1.0);
    }

    @Test
    void skipsTaskWhenItCannotStart(CapturedOutput output) {
        when(taskService.tryStart(99L, false)).thenReturn(OptionalInt.empty());

        useCase().run(99L, false);

        verify(taskRepository, never()).findById(99L);
        verify(analysisEngine, never()).analyze(any());
        verify(taskService, never()).completeSuccess(any(), anyInt());
        assertThat(output).contains("event=analysis_task_skipped taskId=99 redelivered=false reason=not_claimable");
    }

    @Test
    void processesRedeliveredMessageWhenTaskServiceAllowsStart() {
        AnalysisTask task = task(99L);
        when(taskService.tryStart(99L, true)).thenReturn(OptionalInt.of(1));
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(new Resume("resume.docx", "Java Redis Kafka", "summary")));
        when(jobRepository.findById(2L)).thenReturn(Optional.of(new JobDescription(
            "Platform Engineer",
            "Redis Kafka",
            "Redis,Kafka"
        )));
        when(analysisEngine.analyze(any())).thenReturn(new AnalysisResult(90, "score 90 report"));
        when(taskService.completeSuccess(any(), eq(1))).thenReturn(true);

        useCase().run(99L, true);

        verify(taskService).tryStart(99L, true);
        verify(taskService).completeSuccess(any(MatchReport.class), eq(1));
    }

    @Test
    void discardsSuccessWhenExecutionLeaseIsStale(CapturedOutput output) {
        AnalysisTask task = task(99L);
        when(taskService.tryStart(99L, false)).thenReturn(OptionalInt.of(1));
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(new Resume("resume.docx", "Java", "summary")));
        when(jobRepository.findById(2L)).thenReturn(Optional.of(new JobDescription("Engineer", "Java", "Java")));
        when(analysisEngine.analyze(any())).thenReturn(new AnalysisResult(90, "stale report"));
        when(taskService.completeSuccess(any(), eq(1))).thenReturn(false);

        RequestCorrelation.put("stale-success-request", "stale-success-correlation");
        try {
            useCase().run(99L, false);
        } finally {
            RequestCorrelation.clear();
        }

        assertThat(meterRegistry.find("analysis.tasks.succeeded").counter()).isNull();
        assertThat(meterRegistry.counter("analysis.tasks.stale_leases").count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("analysis.worker.duration")
            .tag("outcome", "stale_lease")
            .tag("failureCode", "none")
            .timer()).isNotNull();
        assertThat(output)
            .contains("event=analysis_task_stale_lease taskId=99 expectedAttempt=1 completion=success")
            .contains("failureCode=none")
            .contains("correlationId=stale-success-correlation")
            .doesNotContain("event=analysis_task_succeeded");
    }

    @Test
    void discardsFailureWhenExecutionLeaseIsStaleWithoutCountingOrdinaryFailure(CapturedOutput output) {
        AnalysisTask task = task(99L);
        when(taskService.tryStart(99L, false)).thenReturn(OptionalInt.of(1));
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(new Resume("resume.docx", "Java", "summary")));
        when(jobRepository.findById(2L)).thenReturn(Optional.of(new JobDescription("Engineer", "Java", "Java")));
        when(analysisEngine.analyze(any())).thenThrow(new IllegalStateException("provider body: stale-secret"));
        when(taskService.markRetryableFailure(99L, 1, AnalysisFailureCode.AI_UNAVAILABLE)).thenReturn(false);

        RequestCorrelation.put("stale-failure-request", "stale-failure-correlation");
        try {
            useCase().run(99L, false);
        } finally {
            RequestCorrelation.clear();
        }

        assertThat(meterRegistry.find("analysis.tasks.failed")
            .tag("failureCode", AnalysisFailureCode.AI_UNAVAILABLE.name())
            .counter()).isNull();
        assertThat(meterRegistry.counter("analysis.tasks.stale_leases").count()).isEqualTo(1.0);
        assertThat(output)
            .contains("event=analysis_task_stale_lease taskId=99 expectedAttempt=1 completion=failure")
            .contains("failureCode=AI_UNAVAILABLE")
            .contains("correlationId=stale-failure-correlation")
            .doesNotContain("event=analysis_task_failed")
            .doesNotContain("stale-secret")
            .doesNotContain("provider body");
    }

    private RunAnalysisUseCase useCase() {
        return new RunAnalysisUseCase(
            taskService,
            taskRepository,
            resumeRepository,
            jobRepository,
            analysisEngine,
            new AnalysisMetrics(meterRegistry)
        );
    }

    private AnalysisTask task(Long id) {
        AnalysisTask task = new AnalysisTask(1L, 2L);
        ReflectionTestUtils.setField(task, "id", id);
        return task;
    }
}
