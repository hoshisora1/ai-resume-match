package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.config.AnalysisProperties;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class AnalysisUserDataRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(AnalysisUserDataRetentionScheduler.class);

    private final AnalysisTaskRepository taskRepository;
    private final AnalysisDataDeletionService deletionService;
    private final AnalysisMetrics metrics;
    private final Duration userDataRetention;
    private final int batchSize;
    private final Clock clock;

    @Autowired
    public AnalysisUserDataRetentionScheduler(
        AnalysisTaskRepository taskRepository,
        AnalysisDataDeletionService deletionService,
        AnalysisMetrics metrics,
        AnalysisProperties properties
    ) {
        this(
            taskRepository,
            deletionService,
            metrics,
            properties.retention().userData(),
            properties.retention().batchSize(),
            Clock.systemDefaultZone()
        );
    }

    AnalysisUserDataRetentionScheduler(
        AnalysisTaskRepository taskRepository,
        AnalysisDataDeletionService deletionService,
        AnalysisMetrics metrics,
        Duration userDataRetention,
        int batchSize,
        Clock clock
    ) {
        if (userDataRetention == null || userDataRetention.isZero() || userDataRetention.isNegative()) {
            throw new IllegalArgumentException("analysis.retention.user-data must be positive");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("analysis.retention.batch-size must be positive");
        }
        this.taskRepository = taskRepository;
        this.deletionService = deletionService;
        this.metrics = metrics;
        this.userDataRetention = userDataRetention;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${analysis.retention.scheduler-fixed-delay-ms:3600000}")
    public void purgeExpiredAnalyses() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(userDataRetention);
        List<Long> taskIds = taskRepository.findIdsCreatedBefore(
            cutoff,
            PageRequest.of(0, batchSize)
        );
        int deleted = 0;
        for (Long taskId : taskIds) {
            if (deletionService.deleteExpired(taskId, cutoff)) {
                deleted++;
            }
        }
        metrics.retentionDeleted("analysis_user_data", deleted);
        if (deleted > 0) {
            log.info("event=analysis_user_data_retention_purged deleted={}", deleted);
        }
    }
}
