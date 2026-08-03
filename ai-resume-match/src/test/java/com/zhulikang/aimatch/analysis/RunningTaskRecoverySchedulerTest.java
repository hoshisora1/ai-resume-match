package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.application.analysis.AnalysisTaskPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunningTaskRecoverySchedulerTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC);
    private final LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
    private final LocalDateTime staleBefore = now.minusMinutes(15);

    @Test
    void resetsAndRepublishesStaleRunningTask() {
        AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
        AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
        when(repository.findStaleRunningTaskIds(
            eq(AnalysisTask.Status.RUNNING),
            eq(staleBefore),
            any(Pageable.class)
        )).thenReturn(List.of(99L));
        when(repository.markStaleRunningAsPending(
            99L,
            AnalysisTask.Status.PENDING,
            AnalysisTask.Status.RUNNING,
            staleBefore,
            now
        )).thenReturn(1);
        RunningTaskRecoveryScheduler scheduler = scheduler(repository, publisher);

        scheduler.recoverStaleRunningTasks();

        verify(publisher).publishAfterCommit(99L);
    }

    @Test
    void doesNotPublishWhenAnotherInstanceWinsGuardedReset() {
        AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
        AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
        when(repository.findStaleRunningTaskIds(
            eq(AnalysisTask.Status.RUNNING),
            eq(staleBefore),
            any(Pageable.class)
        )).thenReturn(List.of(99L));
        when(repository.markStaleRunningAsPending(
            99L,
            AnalysisTask.Status.PENDING,
            AnalysisTask.Status.RUNNING,
            staleBefore,
            now
        )).thenReturn(0);
        RunningTaskRecoveryScheduler scheduler = scheduler(repository, publisher);

        scheduler.recoverStaleRunningTasks();

        verify(publisher, never()).publishAfterCommit(99L);
    }

    @Test
    void finalizesExhaustedStaleTaskWithoutRepublishing() {
        AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
        AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
        when(repository.findStaleRunningTaskIds(
            eq(AnalysisTask.Status.RUNNING),
            eq(staleBefore),
            any(Pageable.class)
        )).thenReturn(List.of(99L));
        when(repository.markStaleRunningAsPending(
            99L,
            AnalysisTask.Status.PENDING,
            AnalysisTask.Status.RUNNING,
            staleBefore,
            now
        )).thenReturn(0);
        when(repository.markExhaustedStaleRunningAsFailedFinal(
            99L,
            AnalysisTask.Status.FAILED_FINAL,
            AnalysisTask.Status.RUNNING,
            AnalysisFailureCode.UNEXPECTED_ERROR,
            "Analysis worker timed out and retry attempts are exhausted",
            staleBefore,
            now
        )).thenReturn(1);
        RunningTaskRecoveryScheduler scheduler = scheduler(repository, publisher);

        scheduler.recoverStaleRunningTasks();

        verify(repository).markExhaustedStaleRunningAsFailedFinal(
            99L,
            AnalysisTask.Status.FAILED_FINAL,
            AnalysisTask.Status.RUNNING,
            AnalysisFailureCode.UNEXPECTED_ERROR,
            "Analysis worker timed out and retry attempts are exhausted",
            staleBefore,
            now
        );
        verify(publisher, never()).publishAfterCommit(99L);
    }

    @Test
    void rejectsNonPositiveRunningTimeout() {
        AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
        AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);

        assertThatThrownBy(() -> new RunningTaskRecoveryScheduler(
            repository,
            publisher,
            Duration.ZERO,
            20,
            clock
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("analysis.running-timeout must be positive");
    }

    private RunningTaskRecoveryScheduler scheduler(
        AnalysisTaskRepository repository,
        AnalysisTaskPublisher publisher
    ) {
        return new RunningTaskRecoveryScheduler(
            repository,
            publisher,
            Duration.ofMinutes(15),
            20,
            clock
        );
    }
}
