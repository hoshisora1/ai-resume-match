package com.zhulikang.aimatch.application.analysis;

public record AnalysisSubmissionIdempotencyContext(
    String ownerId,
    String keyHash,
    String requestFingerprint
) {
}
