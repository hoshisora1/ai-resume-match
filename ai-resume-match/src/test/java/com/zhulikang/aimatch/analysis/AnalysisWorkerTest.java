package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.ai.AiClient;
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

class AnalysisWorkerTest {
    private final AnalysisTaskService taskService = mock(AnalysisTaskService.class);
    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private final ResumeRepository resumeRepository = mock(ResumeRepository.class);
    private final JobDescriptionRepository jobRepository = mock(JobDescriptionRepository.class);
    private final AiClient aiClient = mock(AiClient.class);

    @Test
    void createsReportAndMarksTaskSuccess() {
        AnalysisTask task = task(99L);
        when(taskService.tryStart(99L, false)).thenReturn(true);
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(new Resume(
            "resume.docx",
            "项目：高性能秒杀系统，使用 Redis Kafka MySQL 解决库存扣减和异步下单",
            "summary"
        )));
        when(jobRepository.findById(2L)).thenReturn(Optional.of(new JobDescription(
            "要求：Java 后端，熟悉 Redis 和 Kafka",
            "Java,Redis,Kafka"
        )));
        when(aiClient.complete(anyString())).thenReturn("1. 匹配分数：88\n技能匹配：Redis Kafka");

        worker().handle(99L);

        ArgumentCaptor<MatchReport> reportCaptor = ArgumentCaptor.forClass(MatchReport.class);
        verify(taskService).completeSuccess(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getTaskId()).isEqualTo(99L);
        assertThat(reportCaptor.getValue().getMatchScore()).isEqualTo(88);
        assertThat(reportCaptor.getValue().getReportContent()).contains("Redis Kafka");
    }

    @Test
    void marksTaskFailedWithoutRethrowingWhenAiCallFails() {
        AnalysisTask task = task(99L);
        when(taskService.tryStart(99L, false)).thenReturn(true);
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(new Resume("resume.docx", "Java Redis", "summary")));
        when(jobRepository.findById(2L)).thenReturn(Optional.of(new JobDescription("Redis", "Redis")));
        when(aiClient.complete(anyString())).thenThrow(new IllegalStateException("AI unavailable"));

        worker().handle(99L);

        verify(taskService).markFailed(99L);
        verify(taskService, never()).completeSuccess(any());
    }

    @Test
    void skipsTaskWhenItCannotStart() {
        when(taskService.tryStart(99L, false)).thenReturn(false);

        worker().handle(99L);

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
        when(aiClient.complete(anyString())).thenReturn("匹配分数：90");

        worker().handle(99L, true);

        verify(taskService).completeSuccess(any(MatchReport.class));
    }

    private AnalysisWorker worker() {
        return new AnalysisWorker(
            taskService,
            taskRepository,
            resumeRepository,
            jobRepository,
            new TextChunker(),
            new HashingEmbeddingClient(),
            new RagContextBuilder(),
            aiClient,
            new ReportParser()
        );
    }

    private AnalysisTask task(Long id) {
        AnalysisTask task = new AnalysisTask(1L, 2L);
        ReflectionTestUtils.setField(task, "id", id);
        return task;
    }
}
