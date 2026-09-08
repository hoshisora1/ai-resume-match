package com.zhulikang.aimatch.application.analysis;

public interface AnalysisRequestRateLimiter {
    void consume(String ownerId);
}
