package com.zhulikang.aimatch.observability;

import com.zhulikang.aimatch.analysis.AnalysisOutboxRepository;
import com.zhulikang.aimatch.analysis.AnalysisOutboxStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics {
    public OutboxMetrics(AnalysisOutboxRepository repository, MeterRegistry meterRegistry) {
        register(repository, meterRegistry, AnalysisOutboxStatus.PENDING, "pending");
        register(repository, meterRegistry, AnalysisOutboxStatus.FAILED, "failed");
        register(repository, meterRegistry, AnalysisOutboxStatus.PROCESSING, "processing");
    }

    private void register(
        AnalysisOutboxRepository repository,
        MeterRegistry meterRegistry,
        AnalysisOutboxStatus status,
        String tagValue
    ) {
        Gauge.builder("analysis.outbox.backlog", repository, repo -> repo.countByStatus(status))
            .tag("status", tagValue)
            .register(meterRegistry);
    }
}
