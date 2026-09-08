package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.observability.AnalysisMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class AnalysisRetentionSchedulerTest {
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-13T00:00:00Z"),
        ZoneOffset.UTC
    );
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    @Autowired
    AnalysisOutboxRepository outboxRepository;
    @Autowired
    AnalysisSubmissionIdempotencyRepository idempotencyRepository;

    @BeforeEach
    void cleanDatabase() {
        idempotencyRepository.deleteAll();
        outboxRepository.deleteAll();
    }

    @Test
    void purgesExpiredTerminalAndIdempotencyRecordsInBoundedBatches() {
        AnalysisOutboxEvent oldestPublished = AnalysisOutboxEvent.analysisRequested(1L);
        oldestPublished.markPublished(NOW.minusDays(40));
        oldestPublished = outboxRepository.saveAndFlush(oldestPublished);

        AnalysisOutboxEvent oldDead = AnalysisOutboxEvent.analysisRequested(2L);
        assertThat(oldDead.markPublishFailed(
            "broker unavailable",
            NOW.minusDays(35),
            NOW.minusDays(35),
            1
        )).isTrue();
        oldDead = outboxRepository.saveAndFlush(oldDead);

        AnalysisOutboxEvent recentPublished = AnalysisOutboxEvent.analysisRequested(3L);
        recentPublished.markPublished(NOW.minusDays(5));
        recentPublished = outboxRepository.saveAndFlush(recentPublished);
        AnalysisOutboxEvent pending = outboxRepository.saveAndFlush(
            AnalysisOutboxEvent.analysisRequested(4L)
        );

        AnalysisSubmissionIdempotencyRecord expiredIdempotency = idempotencyRepository.saveAndFlush(
            idempotency("a", NOW.minusDays(40))
        );
        AnalysisSubmissionIdempotencyRecord recentIdempotency = idempotencyRepository.saveAndFlush(
            idempotency("b", NOW.minusDays(5))
        );

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AnalysisRetentionScheduler scheduler = scheduler(meterRegistry, 1);

        scheduler.purgeExpiredRecords();

        assertThat(outboxRepository.findById(oldestPublished.getId())).isEmpty();
        assertThat(outboxRepository.findById(oldDead.getId())).isPresent();
        assertThat(idempotencyRepository.findById(expiredIdempotency.getId())).isEmpty();
        assertThat(meterRegistry.counter(
            "analysis.retention.deleted",
            "resource",
            "outbox"
        ).count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
            "analysis.retention.deleted",
            "resource",
            "idempotency"
        ).count()).isEqualTo(1.0);

        scheduler.purgeExpiredRecords();

        assertThat(outboxRepository.findById(oldDead.getId())).isEmpty();
        assertThat(outboxRepository.findById(recentPublished.getId())).isPresent();
        assertThat(outboxRepository.findById(pending.getId())).isPresent();
        assertThat(idempotencyRepository.findById(recentIdempotency.getId())).isPresent();
        assertThat(meterRegistry.counter(
            "analysis.retention.deleted",
            "resource",
            "outbox"
        ).count()).isEqualTo(2.0);
    }

    @Test
    void rejectsUnsafeRetentionBounds() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AnalysisMetrics metrics = new AnalysisMetrics(meterRegistry);

        assertThatThrownBy(() -> new AnalysisRetentionScheduler(
            outboxRepository,
            idempotencyRepository,
            metrics,
            Duration.ZERO,
            Duration.ofDays(30),
            200,
            CLOCK
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retention.outbox");
        assertThatThrownBy(() -> new AnalysisRetentionScheduler(
            outboxRepository,
            idempotencyRepository,
            metrics,
            Duration.ofDays(30),
            Duration.ofDays(30),
            0,
            CLOCK
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch-size");
    }

    private AnalysisRetentionScheduler scheduler(SimpleMeterRegistry meterRegistry, int batchSize) {
        return new AnalysisRetentionScheduler(
            outboxRepository,
            idempotencyRepository,
            new AnalysisMetrics(meterRegistry),
            Duration.ofDays(30),
            Duration.ofDays(30),
            batchSize,
            CLOCK
        );
    }

    private AnalysisSubmissionIdempotencyRecord idempotency(
        String seed,
        LocalDateTime createdAt
    ) {
        AnalysisSubmissionIdempotencyRecord record = new AnalysisSubmissionIdempotencyRecord(
            seed.repeat(64),
            (seed.equals("a") ? "c" : "d").repeat(64)
        );
        ReflectionTestUtils.setField(record, "createdAt", createdAt);
        return record;
    }
}
