package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.application.analysis.AnalysisTaskPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class AnalysisRetryScheduler {
    private final AnalysisTaskRepository taskRepository;
    private final AnalysisTaskPublisher publisher;
    private final int batchSize;
    private final Clock clock;

    @Autowired
    public AnalysisRetryScheduler(
        AnalysisTaskRepository taskRepository,
        AnalysisTaskPublisher publisher,
        @Value("${analysis.retry.batch-size:20}") int batchSize
    ) {
        this(taskRepository, publisher, batchSize, Clock.systemDefaultZone());
    }

    AnalysisRetryScheduler(
        AnalysisTaskRepository taskRepository,
        AnalysisTaskPublisher publisher,
        int batchSize,
        Clock clock
    ) {
        this.taskRepository = taskRepository;
        this.publisher = publisher;
        this.batchSize = Math.max(1, batchSize);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${analysis.retry.scheduler-fixed-delay-ms:30000}")
    @Transactional
    public void enqueueDueRetries() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<AnalysisTask> tasks = taskRepository.findDueRetryableTasks(
            AnalysisTask.Status.FAILED_RETRYABLE,
            now,
            PageRequest.of(0, batchSize)
        );
        for (AnalysisTask task : tasks) {
            int updated = taskRepository.markRetryableAsPending(
                task.getId(),
                AnalysisTask.Status.PENDING,
                AnalysisTask.Status.FAILED_RETRYABLE,
                now
            );
            if (updated == 1) {
                publisher.publishAfterCommit(task.getId());
            }
        }
    }
}
