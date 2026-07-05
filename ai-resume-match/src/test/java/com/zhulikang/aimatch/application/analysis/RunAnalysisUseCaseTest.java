package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.ai.AiClient;
import com.zhulikang.aimatch.analysis.AnalysisFailureCode;
import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.analysis.AnalysisTaskService;
import com.zhulikang.aimatch.analysis.MatchReport;
import com.zhulikang.aimatch.analysis.ReportParser;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.rag.HashingEmbeddingClient;
import com.zhulikang.aimatch.rag.RagContextBuilder;
import com.zhulikang.aimatch.rag.TextChunker;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunAnalysisUseCaseTest {
    private final AnalysisTaskService taskService = mock(AnalysisTaskService.class);
    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private final ResumeRepository resumeRepository = mock(ResumeRepository.class);
    private final JobDescriptionRepository jobRepository = mock(JobDescriptionRepository.class);
    private final AiClient aiClient = mock(AiClient.class);
    private final ReportParser reportParser = mock(ReportParser.class);

    @Test
    void createsReportAndMarksTaskSuccess() {
        AnalysisTask task = task(99L);
        when(taskService.tryStart(99L, false)).thenReturn(true);
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(new Resume(
            "resume.docx",
            "Java Redis Kafka MySQL",
            "summary"
        )));
        when(jobRepository.findById(2L)).thenReturn(Optional.of(new JobDescription(
            "Java backend, Redis and Kafka",
            "Java,Redis,Kafka"
        )));
        when(aiClient.complete(anyString())).thenReturn("report with Redis Kafka");
        when(reportParser.extractScore("report with Redis Kafka")).thenReturn(88);

        useCase().run(99L, false);

        ArgumentCaptor<MatchReport> reportCaptor = ArgumentCaptor.forClass(MatchReport.class);
        verify(taskService).completeSuccess(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getTaskId()).isEqualTo(99L);
        assertThat(reportCaptor.getValue().getMatchScore()).isEqualTo(88);
        assertThat(reportCaptor.getValue().getReportContent()).contains("Redis Kafka");
    }

    @Test
    void marksRetryableFailureWhenAiCallFails() {
        AnalysisTask task = task(99L);
        when(taskService.tryStart(99L, false)).thenReturn(true);
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(new Resume("resume.docx", "Java Redis", "summary")));
        when(jobRepository.findById(2L)).thenReturn(Optional.of(new JobDescription("Redis", "Redis")));
        when(aiClient.complete(anyString())).thenThrow(new IllegalStateException("AI unavailable"));

        useCase().run(99L, false);

        verify(taskService).markRetryableFailure(99L, AnalysisFailureCode.AI_UNAVAILABLE, "AI unavailable");
        verify(taskService, never()).completeSuccess(any());
    }

    @Test
    void marksFinalFailureWhenSourceDataIsMissing() {
        when(taskService.tryStart(99L, false)).thenReturn(true);
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());

        useCase().run(99L, false);

        verify(taskService).markFinalFailure(
            99L,
            AnalysisFailureCode.SOURCE_DATA_MISSING,
            "Analysis source data is missing"
        );
        verify(aiClient, never()).complete(anyString());
    }

    @Test
    void marksFinalFailureWhenReportCannotBeParsed() {
        AnalysisTask task = task(99L);
        when(taskService.tryStart(99L, false)).thenReturn(true);
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(new Resume("resume.docx", "Java Redis", "summary")));
        when(jobRepository.findById(2L)).thenReturn(Optional.of(new JobDescription("Redis", "Redis")));
        when(aiClient.complete(anyString())).thenReturn("no score");
        when(reportParser.extractScore("no score"))
            .thenThrow(new IllegalArgumentException("AI report does not contain score"));

        useCase().run(99L, false);

        verify(taskService).markFinalFailure(
            99L,
            AnalysisFailureCode.REPORT_PARSE_FAILED,
            "AI report does not contain score"
        );
    }

    @Test
    void skipsTaskWhenItCannotStart() {
        when(taskService.tryStart(99L, false)).thenReturn(false);

        useCase().run(99L, false);

        verify(taskRepository, never()).findById(99L);
        verify(aiClient, never()).complete(anyString());
        verify(taskService, never()).completeSuccess(any());
    }

    @Test
    void reprocessesRedeliveredRunningTask() {
        AnalysisTask task = task(99L);
        when(taskService.tryStart(99L, true)).thenReturn(true);
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(new Resume("resume.docx", "Java Redis Kafka", "summary")));
        when(jobRepository.findById(2L)).thenReturn(Optional.of(new JobDescription("Redis Kafka", "Redis,Kafka")));
        when(aiClient.complete(anyString())).thenReturn("score 90 report");
        when(reportParser.extractScore("score 90 report")).thenReturn(90);

        useCase().run(99L, true);

        verify(taskService).tryStart(99L, true);
        verify(taskService).completeSuccess(any(MatchReport.class));
    }

    private RunAnalysisUseCase useCase() {
        return new RunAnalysisUseCase(
            taskService,
            taskRepository,
            resumeRepository,
            jobRepository,
            new TextChunker(),
            new HashingEmbeddingClient(),
            new RagContextBuilder(),
            aiClient,
            reportParser
        );
    }

    private AnalysisTask task(Long id) {
        AnalysisTask task = new AnalysisTask(1L, 2L);
        ReflectionTestUtils.setField(task, "id", id);
        return task;
    }
}
