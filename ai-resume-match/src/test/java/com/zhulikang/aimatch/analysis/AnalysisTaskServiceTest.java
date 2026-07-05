package com.zhulikang.aimatch.analysis;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalysisTaskServiceTest {
    @Test
    void completesSuccessBySavingReportAndUpdatingTaskInOneServiceCall() {
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        MatchReportRepository reportRepository = mock(MatchReportRepository.class);
        AnalysisTaskService service = new AnalysisTaskService(taskRepository, reportRepository, Duration.ofMinutes(15));
        MatchReport report = new MatchReport(99L, 88, "report");
        when(taskRepository.markSuccess(eq(99L), eq(AnalysisTask.Status.SUCCESS), eq(AnalysisTask.Status.RUNNING), any()))
            .thenReturn(1);

        service.completeSuccess(report);

        InOrder inOrder = inOrder(taskRepository, reportRepository);
        inOrder.verify(taskRepository).markSuccess(
            eq(99L),
            eq(AnalysisTask.Status.SUCCESS),
            eq(AnalysisTask.Status.RUNNING),
            any()
        );
        inOrder.verify(reportRepository).save(report);
    }

    @Test
    void doesNotSaveReportWhenTaskCannotTransitionToSuccess() {
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        MatchReportRepository reportRepository = mock(MatchReportRepository.class);
        AnalysisTaskService service = new AnalysisTaskService(taskRepository, reportRepository, Duration.ofMinutes(15));
        MatchReport report = new MatchReport(99L, 88, "report");
        when(taskRepository.markSuccess(eq(99L), eq(AnalysisTask.Status.SUCCESS), eq(AnalysisTask.Status.RUNNING), any()))
            .thenReturn(0);

        assertThatThrownBy(() -> service.completeSuccess(report))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Only running analysis tasks can be completed");
        verifyNoInteractions(reportRepository);
    }

    @Test
    void marksRetryableFailureWithFailureCodeAndMessage() {
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        MatchReportRepository reportRepository = mock(MatchReportRepository.class);
        AnalysisTaskService service = new AnalysisTaskService(
            taskRepository,
            reportRepository,
            Duration.ofMinutes(15),
            Duration.ofMinutes(1)
        );
        AnalysisTask task = new AnalysisTask(1L, 2L);
        task.markRunning();
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));
        when(taskRepository.markFailure(
            eq(99L),
            eq(AnalysisTask.Status.FAILED_RETRYABLE),
            eq(AnalysisTask.Status.RUNNING),
            eq(AnalysisFailureCode.AI_UNAVAILABLE),
            eq("AI unavailable"),
            any(),
            any()
        )).thenReturn(1);

        service.markRetryableFailure(99L, AnalysisFailureCode.AI_UNAVAILABLE, "AI unavailable");

        ArgumentCaptor<LocalDateTime> nextRetryAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskRepository).markFailure(
            eq(99L),
            eq(AnalysisTask.Status.FAILED_RETRYABLE),
            eq(AnalysisTask.Status.RUNNING),
            eq(AnalysisFailureCode.AI_UNAVAILABLE),
            eq("AI unavailable"),
            nextRetryAtCaptor.capture(),
            any()
        );
        assertThat(nextRetryAtCaptor.getValue()).isAfter(LocalDateTime.now().plusSeconds(30));
    }

    @Test
    void marksFinalFailureWhenRetryAttemptsAreExhausted() {
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        MatchReportRepository reportRepository = mock(MatchReportRepository.class);
        AnalysisTaskService service = new AnalysisTaskService(
            taskRepository,
            reportRepository,
            Duration.ofMinutes(15),
            Duration.ofMinutes(1)
        );
        AnalysisTask task = new AnalysisTask(1L, 2L);
        task.markRunning();
        org.springframework.test.util.ReflectionTestUtils.setField(task, "attemptCount", task.getMaxAttempts());
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));
        when(taskRepository.markFailure(
            eq(99L),
            eq(AnalysisTask.Status.FAILED_FINAL),
            eq(AnalysisTask.Status.RUNNING),
            eq(AnalysisFailureCode.AI_UNAVAILABLE),
            eq("AI unavailable"),
            isNull(),
            any()
        )).thenReturn(1);

        service.markRetryableFailure(99L, AnalysisFailureCode.AI_UNAVAILABLE, "AI unavailable");

        verify(taskRepository).markFailure(
            eq(99L),
            eq(AnalysisTask.Status.FAILED_FINAL),
            eq(AnalysisTask.Status.RUNNING),
            eq(AnalysisFailureCode.AI_UNAVAILABLE),
            eq("AI unavailable"),
            isNull(),
            any()
        );
    }

    @Test
    void marksFinalFailureWithFailureCodeAndMessage() {
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        MatchReportRepository reportRepository = mock(MatchReportRepository.class);
        AnalysisTaskService service = new AnalysisTaskService(taskRepository, reportRepository, Duration.ofMinutes(15));
        when(taskRepository.markFailure(
            eq(99L),
            eq(AnalysisTask.Status.FAILED_FINAL),
            eq(AnalysisTask.Status.RUNNING),
            eq(AnalysisFailureCode.SOURCE_DATA_MISSING),
            eq("Analysis source data is missing"),
            isNull(),
            any()
        )).thenReturn(1);

        service.markFinalFailure(99L, AnalysisFailureCode.SOURCE_DATA_MISSING, "Analysis source data is missing");

        verify(taskRepository).markFailure(
            eq(99L),
            eq(AnalysisTask.Status.FAILED_FINAL),
            eq(AnalysisTask.Status.RUNNING),
            eq(AnalysisFailureCode.SOURCE_DATA_MISSING),
            eq("Analysis source data is missing"),
            isNull(),
            any()
        );
    }
}
