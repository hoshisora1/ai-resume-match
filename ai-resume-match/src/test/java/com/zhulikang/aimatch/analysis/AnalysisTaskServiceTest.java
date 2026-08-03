package com.zhulikang.aimatch.analysis;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        when(taskRepository.markSuccess(
            eq(99L),
            eq(AnalysisTask.Status.SUCCESS),
            eq(AnalysisTask.Status.RUNNING),
            eq(1),
            any()
        ))
            .thenReturn(1);

        assertThat(service.completeSuccess(report, 1)).isTrue();

        InOrder inOrder = inOrder(taskRepository, reportRepository);
        inOrder.verify(taskRepository).markSuccess(
            eq(99L),
            eq(AnalysisTask.Status.SUCCESS),
            eq(AnalysisTask.Status.RUNNING),
            eq(1),
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
        when(taskRepository.markSuccess(
            eq(99L),
            eq(AnalysisTask.Status.SUCCESS),
            eq(AnalysisTask.Status.RUNNING),
            eq(1),
            any()
        ))
            .thenReturn(0);

        assertThat(service.completeSuccess(report, 1)).isFalse();
        verifyNoInteractions(reportRepository);
    }

    @Test
    void returnsClaimedAttemptAsExecutionLease() {
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        MatchReportRepository reportRepository = mock(MatchReportRepository.class);
        AnalysisTaskService service = new AnalysisTaskService(taskRepository, reportRepository, Duration.ofMinutes(15));
        AnalysisTask task = new AnalysisTask(1L, 2L);
        task.markRunning();
        when(taskRepository.markRunningIfPendingOrStale(
            eq(99L),
            eq(AnalysisTask.Status.RUNNING),
            eq(AnalysisTask.Status.PENDING),
            any(),
            any()
        )).thenReturn(1);
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));

        OptionalInt claimedAttempt = service.tryStart(99L, false);

        assertThat(claimedAttempt).hasValue(1);
    }

    @Test
    void marksRetryableFailureWithStableUserMessage() {
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
            eq(1),
            eq(AnalysisFailureCode.AI_UNAVAILABLE),
            eq("Analysis service is temporarily unavailable"),
            any(),
            any()
        )).thenReturn(1);

        assertThat(service.markRetryableFailure(99L, 1, AnalysisFailureCode.AI_UNAVAILABLE)).isTrue();

        ArgumentCaptor<LocalDateTime> nextRetryAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskRepository).markFailure(
            eq(99L),
            eq(AnalysisTask.Status.FAILED_RETRYABLE),
            eq(AnalysisTask.Status.RUNNING),
            eq(1),
            eq(AnalysisFailureCode.AI_UNAVAILABLE),
            eq("Analysis service is temporarily unavailable"),
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
            eq(task.getMaxAttempts()),
            eq(AnalysisFailureCode.AI_UNAVAILABLE),
            eq("Analysis service is temporarily unavailable"),
            isNull(),
            any()
        )).thenReturn(1);

        assertThat(service.markRetryableFailure(
            99L,
            task.getMaxAttempts(),
            AnalysisFailureCode.AI_UNAVAILABLE
        )).isTrue();

        verify(taskRepository).markFailure(
            eq(99L),
            eq(AnalysisTask.Status.FAILED_FINAL),
            eq(AnalysisTask.Status.RUNNING),
            eq(task.getMaxAttempts()),
            eq(AnalysisFailureCode.AI_UNAVAILABLE),
            eq("Analysis service is temporarily unavailable"),
            isNull(),
            any()
        );
    }

    @Test
    void marksFinalFailureWithStableUserMessage() {
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        MatchReportRepository reportRepository = mock(MatchReportRepository.class);
        AnalysisTaskService service = new AnalysisTaskService(taskRepository, reportRepository, Duration.ofMinutes(15));
        when(taskRepository.markFailure(
            eq(99L),
            eq(AnalysisTask.Status.FAILED_FINAL),
            eq(AnalysisTask.Status.RUNNING),
            eq(1),
            eq(AnalysisFailureCode.REPORT_PARSE_FAILED),
            eq("Analysis result could not be processed"),
            isNull(),
            any()
        )).thenReturn(1);

        assertThat(service.markFinalFailure(99L, 1, AnalysisFailureCode.REPORT_PARSE_FAILED)).isTrue();

        verify(taskRepository).markFailure(
            eq(99L),
            eq(AnalysisTask.Status.FAILED_FINAL),
            eq(AnalysisTask.Status.RUNNING),
            eq(1),
            eq(AnalysisFailureCode.REPORT_PARSE_FAILED),
            eq("Analysis result could not be processed"),
            isNull(),
            any()
        );
    }

    @Test
    void staleAttemptCannotFailCurrentLease() {
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        MatchReportRepository reportRepository = mock(MatchReportRepository.class);
        AnalysisTaskService service = new AnalysisTaskService(taskRepository, reportRepository, Duration.ofMinutes(15));
        AnalysisTask currentTask = new AnalysisTask(1L, 2L);
        currentTask.markRunning();
        org.springframework.test.util.ReflectionTestUtils.setField(currentTask, "attemptCount", 2);
        when(taskRepository.findById(99L)).thenReturn(Optional.of(currentTask));

        assertThat(service.markRetryableFailure(99L, 1, AnalysisFailureCode.AI_UNAVAILABLE)).isFalse();

        verify(taskRepository, never()).markFailure(
            eq(99L),
            any(),
            any(),
            anyInt(),
            any(),
            any(),
            any(),
            any()
        );
    }

    @Test
    void sanitizesAndTruncatesFailureMessagesDefensively() {
        String raw = "  safe prefix\u0000\r\n\t" + "x".repeat(300) + "\u202E  ";

        String sanitized = AnalysisTaskService.sanitizeFailureMessage(raw);

        assertThat(sanitized)
            .startsWith("safe prefix ")
            .doesNotContain("\u0000", "\r", "\n", "\t", "\u202E")
            .hasSizeLessThanOrEqualTo(AnalysisTaskService.MAX_FAILURE_MESSAGE_LENGTH);
        assertThat(AnalysisTaskService.sanitizeFailureMessage("\u0000\r\n"))
            .isEqualTo("Analysis failed");
    }
}
