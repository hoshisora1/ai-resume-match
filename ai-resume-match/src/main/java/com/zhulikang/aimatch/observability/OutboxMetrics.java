package com.zhulikang.aimatch.observability;

import com.zhulikang.aimatch.analysis.AnalysisOutboxRepository;
import com.zhulikang.aimatch.analysis.AnalysisOutboxStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

@Component
public class OutboxMetrics {
    private static final Set<AnalysisOutboxStatus> NON_TERMINAL_STATUSES = EnumSet.of(
        AnalysisOutboxStatus.PENDING,
        AnalysisOutboxStatus.FAILED,
        AnalysisOutboxStatus.PROCESSING
    );

    @Autowired
    public OutboxMetrics(AnalysisOutboxRepository repository, MeterRegistry meterRegistry) {
        this(repository, meterRegistry, Clock.systemDefaultZone());
    }

    OutboxMetrics(AnalysisOutboxRepository repository, MeterRegistry meterRegistry, Clock clock) {
        register(repository, meterRegistry, AnalysisOutboxStatus.PENDING, "pending");
        register(repository, meterRegistry, AnalysisOutboxStatus.FAILED, "failed");
        register(repository, meterRegistry, AnalysisOutboxStatus.PROCESSING, "processing");
        register(repository, meterRegistry, AnalysisOutboxStatus.DEAD, "dead");
        Gauge.builder(
                "analysis.outbox.oldest.age",
                repository,
                repo -> oldestNonTerminalAgeSeconds(repo, clock)
            )
            .baseUnit("seconds")
            .description("Age in seconds of the oldest non-terminal outbox event")
            .register(meterRegistry);
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

    private double oldestNonTerminalAgeSeconds(AnalysisOutboxRepository repository, Clock clock) {
        return repository.findOldestCreatedAtByStatuses(NON_TERMINAL_STATUSES)
            .map(createdAt -> Duration.between(createdAt, LocalDateTime.now(clock)).toSeconds())
            .map(age -> Math.max(0L, age))
            .map(Long::doubleValue)
            .orElse(0.0);
    }
}
