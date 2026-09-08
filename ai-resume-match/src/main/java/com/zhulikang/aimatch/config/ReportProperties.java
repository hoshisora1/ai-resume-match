package com.zhulikang.aimatch.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("report")
public record ReportProperties(@NotNull Duration cacheTtl) {
    @AssertTrue(message = "report.cache-ttl must be positive")
    public boolean isCacheTtlValid() {
        return cacheTtl != null && !cacheTtl.isZero() && !cacheTtl.isNegative();
    }
}
