package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.config.AnalysisProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

@Component
public class OutboxRetryPolicy {
    private final long baseDelayMillis;
    private final long maxDelayMillis;
    private final double jitterRatio;
    private final DoubleSupplier randomSample;

    @Autowired
    public OutboxRetryPolicy(AnalysisProperties properties) {
        this(
            properties.outbox().retryDelay(),
            properties.outbox().retryMaxDelay(),
            properties.outbox().retryJitterRatio(),
            () -> ThreadLocalRandom.current().nextDouble()
        );
    }

    OutboxRetryPolicy(
        Duration baseDelay,
        Duration maxDelay,
        double jitterRatio,
        DoubleSupplier randomSample
    ) {
        this.baseDelayMillis = positiveMillis(baseDelay, "analysis.outbox.retry-delay");
        this.maxDelayMillis = positiveMillis(maxDelay, "analysis.outbox.retry-max-delay");
        if (maxDelayMillis < baseDelayMillis) {
            throw new IllegalArgumentException(
                "analysis.outbox.retry-max-delay must be greater than or equal to retry-delay"
            );
        }
        if (!Double.isFinite(jitterRatio) || jitterRatio < 0.0 || jitterRatio > 1.0) {
            throw new IllegalArgumentException(
                "analysis.outbox.retry-jitter-ratio must be between 0 and 1"
            );
        }
        this.jitterRatio = jitterRatio;
        this.randomSample = Objects.requireNonNull(randomSample, "randomSample must not be null");
    }

    public Duration delayForAttempt(int failedAttemptNumber) {
        if (failedAttemptNumber < 1) {
            throw new IllegalArgumentException("failedAttemptNumber must be positive");
        }

        long exponentialDelay = baseDelayMillis;
        for (int attempt = 1; attempt < failedAttemptNumber && exponentialDelay < maxDelayMillis; attempt++) {
            if (exponentialDelay > maxDelayMillis / 2) {
                exponentialDelay = maxDelayMillis;
            } else {
                exponentialDelay = Math.min(maxDelayMillis, exponentialDelay * 2);
            }
        }

        double sample = randomSample.getAsDouble();
        if (!Double.isFinite(sample)) {
            sample = 0.5;
        }
        sample = Math.max(0.0, Math.min(1.0, sample));
        double multiplier = 1.0 + ((sample * 2.0) - 1.0) * jitterRatio;
        long jitteredDelay = Math.round(exponentialDelay * multiplier);
        return Duration.ofMillis(Math.max(1L, Math.min(maxDelayMillis, jitteredDelay)));
    }

    private static long positiveMillis(Duration duration, String propertyName) {
        if (duration == null || duration.isZero() || duration.isNegative() || duration.toMillis() < 1) {
            throw new IllegalArgumentException(propertyName + " must be at least 1ms");
        }
        return duration.toMillis();
    }
}
