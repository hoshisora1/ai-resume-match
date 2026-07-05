package com.zhulikang.aimatch.observability;

import com.zhulikang.aimatch.analysis.AnalysisOutboxRepository;
import com.zhulikang.aimatch.analysis.AnalysisOutboxStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxMetricsTest {
    @Test
    void exposesOutboxBacklogGaugesByStatus() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        when(repository.countByStatus(AnalysisOutboxStatus.PENDING)).thenReturn(3L);
        when(repository.countByStatus(AnalysisOutboxStatus.FAILED)).thenReturn(2L);
        when(repository.countByStatus(AnalysisOutboxStatus.PROCESSING)).thenReturn(1L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OutboxMetrics(repository, registry);

        assertThat(registry.get("analysis.outbox.backlog").tag("status", "pending").gauge().value())
            .isEqualTo(3.0);
        assertThat(registry.get("analysis.outbox.backlog").tag("status", "failed").gauge().value())
            .isEqualTo(2.0);
        assertThat(registry.get("analysis.outbox.backlog").tag("status", "processing").gauge().value())
            .isEqualTo(1.0);
    }
}
