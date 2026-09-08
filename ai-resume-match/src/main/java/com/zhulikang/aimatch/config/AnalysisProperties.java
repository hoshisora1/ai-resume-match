package com.zhulikang.aimatch.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("analysis")
public record AnalysisProperties(
    @NotBlank
    @Pattern(regexp = "agent|legacy", message = "must be agent or legacy")
    String engine,
    @NotNull @Valid RateLimit rateLimit,
    @NotNull @Valid Scheduling scheduling,
    @NotNull Duration runningTimeout,
    @NotNull @Valid RunningRecovery runningRecovery,
    @NotNull @Valid Outbox outbox,
    @NotNull @Valid Retention retention,
    @NotNull @Valid Retry retry
) {
    @AssertTrue(message = "analysis.running-timeout must be positive")
    public boolean isRunningTimeoutValid() {
        return positive(runningTimeout);
    }

    public record RateLimit(
        @NotNull Boolean enabled,
        @NotNull @Positive Integer maxRequests,
        @NotNull Duration window
    ) {
        @AssertTrue(message = "analysis.rate-limit.window must be positive")
        public boolean isWindowValid() {
            return positive(window);
        }
    }

    public record Scheduling(@NotNull Boolean enabled) {
    }

    public record RunningRecovery(
        @NotNull @Positive Long schedulerFixedDelayMs,
        @NotNull @Positive Integer batchSize
    ) {
    }

    public record Outbox(
        @NotNull @Positive Long fixedDelayMs,
        @NotNull @Positive Integer batchSize,
        @NotNull @Positive Integer maxAttempts,
        @NotNull Duration retryDelay,
        @NotNull Duration retryMaxDelay,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double retryJitterRatio,
        @NotNull Duration confirmTimeout,
        @NotNull Duration leaseDuration
    ) {
        @AssertTrue(message = "analysis.outbox durations must be positive")
        public boolean isDurationBudgetValid() {
            return positive(retryDelay)
                && positive(retryMaxDelay)
                && positive(confirmTimeout)
                && positive(leaseDuration);
        }

        @AssertTrue(message = "analysis.outbox.retry-max-delay must not be shorter than retry-delay")
        public boolean isRetryRangeValid() {
            return retryDelay != null
                && retryMaxDelay != null
                && retryMaxDelay.compareTo(retryDelay) >= 0;
        }

        @AssertTrue(message = "analysis.outbox.retry-jitter-ratio must be finite")
        public boolean isRetryJitterFinite() {
            return retryJitterRatio != null && Double.isFinite(retryJitterRatio);
        }
    }

    public record Retention(
        @NotNull @Positive Long schedulerFixedDelayMs,
        @NotNull @Positive Integer batchSize,
        @NotNull Duration outbox,
        @NotNull Duration idempotency,
        @NotNull Duration userData
    ) {
        @AssertTrue(message = "analysis retention durations must be positive")
        public boolean isDurationBudgetValid() {
            return positive(outbox) && positive(idempotency) && positive(userData);
        }
    }

    public record Retry(
        @NotNull Duration delay,
        @NotNull Duration maxDelay,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double jitterRatio,
        @NotNull @Positive Long schedulerFixedDelayMs,
        @NotNull @Positive Integer batchSize
    ) {
        @AssertTrue(message = "analysis.retry durations must be positive")
        public boolean isDurationBudgetValid() {
            return positive(delay) && positive(maxDelay);
        }

        @AssertTrue(message = "analysis.retry.max-delay must not be shorter than delay")
        public boolean isRetryRangeValid() {
            return delay != null && maxDelay != null && maxDelay.compareTo(delay) >= 0;
        }

        @AssertTrue(message = "analysis.retry.jitter-ratio must be finite")
        public boolean isRetryJitterFinite() {
            return jitterRatio != null && Double.isFinite(jitterRatio);
        }
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
