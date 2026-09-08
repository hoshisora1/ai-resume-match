package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.config.AnalysisProperties;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class AnalysisRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(AnalysisRetentionScheduler.class);
    private static final List<AnalysisOutboxStatus> TERMINAL_OUTBOX_STATUSES = List.of(
        AnalysisOutboxStatus.PUBLISHED,
        AnalysisOutboxStatus.DEAD
    );
    private static final List<AnalysisTask.Status> TERMINAL_TASK_STATUSES = List.of(
        AnalysisTask.Status.SUCCESS,
        AnalysisTask.Status.FAILED_FINAL,
        AnalysisTask.Status.CANCELLED,
        AnalysisTask.Status.FAILED
    );

    private final AnalysisOutboxRepository outboxRepository;
    private final AnalysisSubmissionIdempotencyRepository idempotencyRepository;
    private final AnalysisMetrics metrics;
    private final Duration outboxRetention;
    private final Duration idempotencyRetention;
    private final int batchSize;
    private final Clock clock;

    @Autowired
    public AnalysisRetentionScheduler(
        AnalysisOutboxRepository outboxRepository,
        AnalysisSubmissionIdempotencyRepository idempotencyRepository,
        AnalysisMetrics metrics,
        AnalysisProperties properties
    ) {
        this(
            outboxRepository,
            idempotencyRepository,
            metrics,
            properties.retention().outbox(),
            properties.retention().idempotency(),
            properties.retention().batchSize(),
            Clock.systemDefaultZone()
        );
    }

    AnalysisRetentionScheduler(
        AnalysisOutboxRepository outboxRepository,
        AnalysisSubmissionIdempotencyRepository idempotencyRepository,
        AnalysisMetrics metrics,
        Duration outboxRetention,
        Duration idempotencyRetention,
        int batchSize,
        Clock clock
    ) {
        this.outboxRepository = outboxRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.metrics = metrics;
        this.outboxRetention = requirePositive(outboxRetention, "analysis.retention.outbox");
        this.idempotencyRetention = requirePositive(
            idempotencyRetention,
            "analysis.retention.idempotency"
        );
        if (batchSize < 1) {
            throw new IllegalArgumentException("analysis.retention.batch-size must be positive");
        }
        this.batchSize = batchSize;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${analysis.retention.scheduler-fixed-delay-ms:3600000}")
    @Transactional
    public void purgeExpiredRecords() {
        LocalDateTime now = LocalDateTime.now(clock);
        int outboxDeleted = purgeOutbox(now.minus(outboxRetention));
        int idempotencyDeleted = purgeIdempotency(now.minus(idempotencyRetention));
        metrics.retentionDeleted("outbox", outboxDeleted);
        metrics.retentionDeleted("idempotency", idempotencyDeleted);
        if (outboxDeleted > 0 || idempotencyDeleted > 0) {
            log.info(
                "event=analysis_retention_purged outboxDeleted={} idempotencyDeleted={}",
                outboxDeleted,
                idempotencyDeleted
            );
        }
    }

    private int purgeOutbox(LocalDateTime cutoff) {
        List<Long> eventIds = outboxRepository.findTerminalIdsBefore(
            TERMINAL_OUTBOX_STATUSES,
            cutoff,
            PageRequest.of(0, batchSize)
        );
        if (eventIds.isEmpty()) {
            return 0;
        }
        return outboxRepository.deleteTerminalEvents(
            eventIds,
            TERMINAL_OUTBOX_STATUSES,
            cutoff
        );
    }

    private int purgeIdempotency(LocalDateTime cutoff) {
        List<Long> recordIds = idempotencyRepository.findIdsCreatedBefore(
            cutoff,
            TERMINAL_TASK_STATUSES,
            PageRequest.of(0, batchSize)
        );
        if (recordIds.isEmpty()) {
            return 0;
        }
        return idempotencyRepository.deleteCreatedBefore(
            recordIds,
            cutoff,
            TERMINAL_TASK_STATUSES
        );
    }

    private static Duration requirePositive(Duration duration, String propertyName) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
        return duration;
    }
}
