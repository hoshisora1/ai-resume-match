package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.observability.AnalysisMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisUserDataRetentionSchedulerTest {
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-13T00:00:00Z"),
        ZoneOffset.UTC
    );

    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private final AnalysisDataDeletionService deletionService = mock(AnalysisDataDeletionService.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void deletesRevalidatedAgeCandidatesWithinTheConfiguredBatch() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 7, 14, 0, 0);
        when(taskRepository.findIdsCreatedBefore(
            eq(cutoff),
            org.mockito.ArgumentMatchers.any(Pageable.class)
        )).thenReturn(List.of(11L, 12L));
        when(deletionService.deleteExpired(11L, cutoff)).thenReturn(true);

        scheduler(Duration.ofDays(30), 2).purgeExpiredAnalyses();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(taskRepository).findIdsCreatedBefore(
            eq(cutoff),
            pageable.capture()
        );
        assertThat(pageable.getValue().getPageSize()).isEqualTo(2);
        verify(deletionService).deleteExpired(12L, cutoff);
        assertThat(meterRegistry.counter(
            "analysis.retention.deleted",
            "resource",
            "analysis_user_data"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void rejectsUnsafeRetentionConfiguration() {
        assertThatThrownBy(() -> scheduler(Duration.ZERO, 20))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("retention.user-data");
        assertThatThrownBy(() -> scheduler(Duration.ofDays(30), 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("batch-size");
    }

    private AnalysisUserDataRetentionScheduler scheduler(Duration retention, int batchSize) {
        return new AnalysisUserDataRetentionScheduler(
            taskRepository,
            deletionService,
            new AnalysisMetrics(meterRegistry),
            retention,
            batchSize,
            CLOCK
        );
    }
}
