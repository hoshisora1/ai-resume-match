package com.zhulikang.aimatch.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("api")
public record ApiProperties(
    @NotBlank String token,
    @NotBlank @Size(min = 32) String sessionSigningKey,
    @NotNull Duration sessionTtl,
    @NotNull Boolean sessionCookieSecure
) {
    @AssertTrue(message = "api.session-ttl must be positive")
    public boolean isSessionTtlValid() {
        return sessionTtl != null && !sessionTtl.isZero() && !sessionTtl.isNegative();
    }
}
