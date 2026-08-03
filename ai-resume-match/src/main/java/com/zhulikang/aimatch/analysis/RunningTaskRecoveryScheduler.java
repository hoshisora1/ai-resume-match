package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.application.analysis.AnalysisTaskPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class RunningTaskRecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(RunningTaskRecoveryScheduler.class);
    private static final String EXHAUSTED_FAILURE_MESSAGE =
        "Analysis worker timed out and retry attempts are exhausted";

    private final AnalysisTaskRepository taskRepository;
    private final AnalysisTaskPublisher publisher;
    private final Duration runningTimeout;
    private final int batchSize;
    private final Clock clock;

    @Autowired
    public RunningTaskRecoveryScheduler(
        AnalysisTaskRepository taskRepository,
        AnalysisTaskPublisher publisher,
        @Value("${analysis.running-timeout:15m}") Duration runningTimeout,
        @Value("${analysis.running-recovery.batch-size:20}") int batchSize
    ) {
        this(taskRepository, publisher, runningTimeout, batchSize, Clock.systemDefaultZone());
    }

    RunningTaskRecoveryScheduler(
        AnalysisTaskRepository taskRepository,
        AnalysisTaskPublisher publisher,
        Duration runningTimeout,
        int batchSize,
        Clock clock
    ) {
        if (runningTimeout.isZero() || runningTimeout.isNegative()) {
            throw new IllegalArgumentException("analysis.running-timeout must be positive");
        }
        this.taskRepository = taskRepository;
        this.publisher = publisher;
        this.runningTimeout = runningTimeout;
        this.batchSize = Math.max(1, batchSize);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${analysis.running-recovery.scheduler-fixed-delay-ms:30000}")
    @Transactional
    public void recoverStaleRunningTasks() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime staleBefore = now.minus(runningTimeout);
        List<Long> taskIds = taskRepository.findStaleRunningTaskIds(
            AnalysisTask.Status.RUNNING,
            staleBefore,
            PageRequest.of(0, batchSize)
        );

        for (Long taskId : taskIds) {
            int updated = taskRepository.markStaleRunningAsPending(
                taskId,
                AnalysisTask.Status.PENDING,
                AnalysisTask.Status.RUNNING,
                staleBefore,
                now
            );
            if (updated == 1) {
                publisher.publishAfterCommit(taskId);
                log.warn("event=analysis_task_recovered taskId={} staleBefore={}", taskId, staleBefore);
                continue;
            }

            int finalized = taskRepository.markExhaustedStaleRunningAsFailedFinal(
                taskId,
                AnalysisTask.Status.FAILED_FINAL,
                AnalysisTask.Status.RUNNING,
                AnalysisFailureCode.UNEXPECTED_ERROR,
                EXHAUSTED_FAILURE_MESSAGE,
                staleBefore,
                now
            );
            if (finalized == 1) {
                log.error(
                    "event=analysis_task_recovery_exhausted taskId={} staleBefore={} failureCode={}",
                    taskId,
                    staleBefore,
                    AnalysisFailureCode.UNEXPECTED_ERROR
                );
            }
        }
    }
}
