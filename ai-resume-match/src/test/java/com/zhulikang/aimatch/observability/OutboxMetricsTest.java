package com.zhulikang.aimatch.observability;

import com.zhulikang.aimatch.analysis.AnalysisOutboxRepository;
import com.zhulikang.aimatch.analysis.AnalysisOutboxStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxMetricsTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void exposesOutboxBacklogGaugesByStatus() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        when(repository.countByStatus(AnalysisOutboxStatus.PENDING)).thenReturn(3L);
        when(repository.countByStatus(AnalysisOutboxStatus.FAILED)).thenReturn(2L);
        when(repository.countByStatus(AnalysisOutboxStatus.PROCESSING)).thenReturn(1L);
        when(repository.countByStatus(AnalysisOutboxStatus.DEAD)).thenReturn(4L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        when(repository.findOldestCreatedAtByStatuses(anyCollection())).thenReturn(Optional.empty());

        new OutboxMetrics(repository, registry, CLOCK);

        assertThat(registry.get("analysis.outbox.backlog").tag("status", "pending").gauge().value())
            .isEqualTo(3.0);
        assertThat(registry.get("analysis.outbox.backlog").tag("status", "failed").gauge().value())
            .isEqualTo(2.0);
        assertThat(registry.get("analysis.outbox.backlog").tag("status", "processing").gauge().value())
            .isEqualTo(1.0);
        assertThat(registry.get("analysis.outbox.backlog").tag("status", "dead").gauge().value())
            .isEqualTo(4.0);
    }

    @Test
    void exposesAgeOfOldestNonTerminalEventWithoutNegativeClockSkew() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        when(repository.findOldestCreatedAtByStatuses(anyCollection()))
            .thenReturn(Optional.of(LocalDateTime.of(2026, 8, 17, 23, 57)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OutboxMetrics(repository, registry, CLOCK);

        assertThat(registry.get("analysis.outbox.oldest.age").gauge().value()).isEqualTo(180.0);

        when(repository.findOldestCreatedAtByStatuses(anyCollection()))
            .thenReturn(Optional.of(LocalDateTime.of(2026, 8, 18, 0, 1)));
        assertThat(registry.get("analysis.outbox.oldest.age").gauge().value()).isZero();
    }
}
