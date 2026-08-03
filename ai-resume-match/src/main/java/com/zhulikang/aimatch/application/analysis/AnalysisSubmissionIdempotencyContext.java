package com.zhulikang.aimatch.application.analysis;

public record AnalysisSubmissionIdempotencyContext(
    String keyHash,
    String requestFingerprint
) {
}
