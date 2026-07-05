package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.application.analysis.AnalysisTaskPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisRetrySchedulerTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC);
    private final LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());

    @Test
    void republishesDueRetryableTasksThroughOutbox() {
        AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
        AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
        AnalysisTask task = retryableTask(99L);
        when(repository.findDueRetryableTasks(eq(AnalysisTask.Status.FAILED_RETRYABLE), eq(now), any(Pageable.class)))
            .thenReturn(List.of(task));
        when(repository.markRetryableAsPending(99L, AnalysisTask.Status.PENDING, AnalysisTask.Status.FAILED_RETRYABLE, now))
            .thenReturn(1);
        AnalysisRetryScheduler scheduler = new AnalysisRetryScheduler(repository, publisher, 20, clock);

        scheduler.enqueueDueRetries();

        verify(repository).markRetryableAsPending(
            99L,
            AnalysisTask.Status.PENDING,
            AnalysisTask.Status.FAILED_RETRYABLE,
            now
        );
        verify(publisher).publishAfterCommit(99L);
    }

    @Test
    void skipsPublishWhenGuardedTransitionFails() {
        AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
        AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
        AnalysisTask task = retryableTask(99L);
        when(repository.findDueRetryableTasks(eq(AnalysisTask.Status.FAILED_RETRYABLE), eq(now), any(Pageable.class)))
            .thenReturn(List.of(task));
        when(repository.markRetryableAsPending(99L, AnalysisTask.Status.PENDING, AnalysisTask.Status.FAILED_RETRYABLE, now))
            .thenReturn(0);
        AnalysisRetryScheduler scheduler = new AnalysisRetryScheduler(repository, publisher, 20, clock);

        scheduler.enqueueDueRetries();

        verify(publisher, never()).publishAfterCommit(99L);
    }

    private AnalysisTask retryableTask(Long id) {
        AnalysisTask task = new AnalysisTask(1L, 2L);
        ReflectionTestUtils.setField(task, "id", id);
        task.markRunning();
        task.markRetryableFailure(AnalysisFailureCode.AI_UNAVAILABLE, "AI unavailable", now.minusSeconds(1));
        return task;
    }
}
