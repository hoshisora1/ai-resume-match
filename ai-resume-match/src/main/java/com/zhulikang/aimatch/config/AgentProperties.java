package com.zhulikang.aimatch.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("agent")
public record AgentProperties(
    @NotBlank
    @Pattern(regexp = "https?://\\S+", message = "must be an absolute HTTP(S) URL")
    String baseUrl,
    @NotBlank String token,
    @NotNull Duration connectTimeout,
    @NotNull Duration readTimeout
) {
    @AssertTrue(message = "agent timeouts must be positive and read-timeout must exceed connect-timeout")
    public boolean isTimeoutBudgetValid() {
        return isPositive(connectTimeout)
            && isPositive(readTimeout)
            && readTimeout.compareTo(connectTimeout) > 0;
    }

    private static boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
