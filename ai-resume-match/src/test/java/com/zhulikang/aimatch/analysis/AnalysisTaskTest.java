package com.zhulikang.aimatch.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisTaskTest {
    @Test
    void startsPendingTaskAndIncrementsAttempt() {
        AnalysisTask task = new AnalysisTask(1L, 2L);

        task.markRunning();

        assertThat(task.getStatus()).isEqualTo(AnalysisTask.Status.RUNNING);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getStartedAt()).isNotNull();
        assertThat(task.getUpdatedAt()).isNotNull();
    }

    @Test
    void marksRetryableFailureWithMetadata() {
        AnalysisTask task = new AnalysisTask(1L, 2L);
        task.markRunning();

        task.markRetryableFailure(AnalysisFailureCode.AI_UNAVAILABLE, "AI unavailable");

        assertThat(task.getStatus()).isEqualTo(AnalysisTask.Status.FAILED_RETRYABLE);
        assertThat(task.getFailureCode()).isEqualTo(AnalysisFailureCode.AI_UNAVAILABLE);
        assertThat(task.getFailureMessage()).isEqualTo("AI unavailable");
        assertThat(task.getCompletedAt()).isNotNull();
    }

    @Test
    void retriesOnlyRetryableFailures() {
        AnalysisTask task = new AnalysisTask(1L, 2L);
        task.markRunning();
        task.markRetryableFailure(AnalysisFailureCode.AI_UNAVAILABLE, "AI unavailable");

        task.retry();

        assertThat(task.getStatus()).isEqualTo(AnalysisTask.Status.PENDING);
        assertThat(task.getFailureCode()).isNull();
        assertThat(task.getFailureMessage()).isNull();
        assertThat(task.getCompletedAt()).isNull();
    }

    @Test
    void rejectsRetryForFinalFailure() {
        AnalysisTask task = new AnalysisTask(1L, 2L);
        task.markRunning();
        task.markFinalFailure(AnalysisFailureCode.SOURCE_DATA_MISSING, "Missing source data");

        assertThatThrownBy(task::retry)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Only retryable failed analysis tasks can be retried");
    }
}
