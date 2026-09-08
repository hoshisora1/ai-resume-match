package com.zhulikang.aimatch.analysis;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisRetryPolicyTest {
    @Test
    void appliesCappedExponentialBackoffWithoutOverflow() {
        AnalysisRetryPolicy policy = policy(Duration.ofMinutes(1), Duration.ofMinutes(15), 0.0, 0.5);

        assertThat(policy.delayForAttempt(1, null)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.delayForAttempt(2, null)).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.delayForAttempt(3, null)).isEqualTo(Duration.ofMinutes(4));
        assertThat(policy.delayForAttempt(4, null)).isEqualTo(Duration.ofMinutes(8));
        assertThat(policy.delayForAttempt(5, null)).isEqualTo(Duration.ofMinutes(15));
        assertThat(policy.delayForAttempt(Integer.MAX_VALUE, null)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void appliesBoundedJitter() {
        assertThat(policy(Duration.ofMinutes(1), Duration.ofMinutes(15), 0.2, 0.0)
            .delayForAttempt(1, null)).isEqualTo(Duration.ofSeconds(48));
        assertThat(policy(Duration.ofMinutes(1), Duration.ofMinutes(15), 0.2, 0.5)
            .delayForAttempt(1, null)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy(Duration.ofMinutes(1), Duration.ofMinutes(15), 0.2, 1.0)
            .delayForAttempt(1, null)).isEqualTo(Duration.ofSeconds(72));
    }

    @Test
    void treatsRetryAfterAsMinimumWaitAndCapsUntrustedValues() {
        AnalysisRetryPolicy lowJitter = policy(Duration.ofMinutes(1), Duration.ofMinutes(15), 0.2, 0.0);

        assertThat(lowJitter.delayForAttempt(1, 90)).isEqualTo(Duration.ofSeconds(90));
        assertThat(lowJitter.delayForAttempt(2, 30)).isEqualTo(Duration.ofSeconds(96));
        assertThat(lowJitter.delayForAttempt(1, Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(15));
        assertThat(lowJitter.delayForAttempt(1, 0)).isEqualTo(Duration.ofSeconds(48));
        assertThat(lowJitter.delayForAttempt(1, -1)).isEqualTo(Duration.ofSeconds(48));
    }

    @Test
    void normalizesInvalidRandomSamplesAndRejectsInvalidPolicyBounds() {
        assertThat(policy(Duration.ofMinutes(1), Duration.ofMinutes(15), 0.2, Double.NaN)
            .delayForAttempt(1, null)).isEqualTo(Duration.ofMinutes(1));
        assertThatThrownBy(() -> policy(Duration.ZERO, Duration.ofMinutes(1), 0.2, 0.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("analysis.retry.delay");
        assertThatThrownBy(() -> policy(Duration.ofMinutes(2), Duration.ofMinutes(1), 0.2, 0.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("analysis.retry.max-delay");
        assertThatThrownBy(() -> policy(Duration.ofSeconds(1), Duration.ofMinutes(1), 1.1, 0.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("analysis.retry.jitter-ratio");
        assertThatThrownBy(() -> policy(Duration.ofSeconds(1), Duration.ofMinutes(1), 0.2, 0.5)
            .delayForAttempt(0, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("failedAttemptNumber");
    }

    private AnalysisRetryPolicy policy(
        Duration baseDelay,
        Duration maxDelay,
        double jitterRatio,
        double randomSample
    ) {
        return new AnalysisRetryPolicy(baseDelay, maxDelay, jitterRatio, () -> randomSample);
    }
}
