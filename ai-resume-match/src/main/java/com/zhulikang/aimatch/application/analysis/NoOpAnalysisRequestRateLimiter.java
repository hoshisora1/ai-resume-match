package com.zhulikang.aimatch.application.analysis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "analysis.rate-limit.enabled", havingValue = "false")
public class NoOpAnalysisRequestRateLimiter implements AnalysisRequestRateLimiter {
    @Override
    public void consume(String ownerId) {
        // Explicitly disabled for isolated tests and trusted local development only.
    }
}
