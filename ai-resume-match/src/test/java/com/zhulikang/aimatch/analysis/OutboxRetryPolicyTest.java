package com.zhulikang.aimatch.analysis;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxRetryPolicyTest {
    @Test
    void appliesCappedExponentialBackoffWithoutOverflow() {
        OutboxRetryPolicy policy = policy(Duration.ofSeconds(30), Duration.ofMinutes(5), 0.0, 0.5);

        assertThat(policy.delayForAttempt(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.delayForAttempt(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.delayForAttempt(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.delayForAttempt(4)).isEqualTo(Duration.ofMinutes(4));
        assertThat(policy.delayForAttempt(5)).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.delayForAttempt(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void appliesBoundedSymmetricJitterAndNeverExceedsTheCap() {
        assertThat(policy(Duration.ofSeconds(30), Duration.ofMinutes(5), 0.2, 0.0)
            .delayForAttempt(1)).isEqualTo(Duration.ofSeconds(24));
        assertThat(policy(Duration.ofSeconds(30), Duration.ofMinutes(5), 0.2, 0.5)
            .delayForAttempt(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy(Duration.ofSeconds(30), Duration.ofMinutes(5), 0.2, 1.0)
            .delayForAttempt(1)).isEqualTo(Duration.ofSeconds(36));
        assertThat(policy(Duration.ofMinutes(4), Duration.ofMinutes(5), 0.5, 1.0)
            .delayForAttempt(2)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void rejectsInvalidPolicyBoundsAndAttemptNumbers() {
        assertThatThrownBy(() -> policy(Duration.ZERO, Duration.ofMinutes(1), 0.2, 0.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("retry-delay");
        assertThatThrownBy(() -> policy(Duration.ofMinutes(2), Duration.ofMinutes(1), 0.2, 0.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("retry-max-delay");
        assertThatThrownBy(() -> policy(Duration.ofSeconds(1), Duration.ofMinutes(1), 1.1, 0.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("retry-jitter-ratio");
        assertThatThrownBy(() -> policy(Duration.ofSeconds(1), Duration.ofMinutes(1), 0.2, 0.5)
            .delayForAttempt(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("failedAttemptNumber");
    }

    private OutboxRetryPolicy policy(
        Duration baseDelay,
        Duration maxDelay,
        double jitterRatio,
        double randomSample
    ) {
        return new OutboxRetryPolicy(baseDelay, maxDelay, jitterRatio, () -> randomSample);
    }
}
