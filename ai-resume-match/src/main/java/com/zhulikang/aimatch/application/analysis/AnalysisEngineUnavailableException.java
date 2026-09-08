package com.zhulikang.aimatch.application.analysis;

final class AnalysisEngineUnavailableException extends RuntimeException {
    private final Integer retryAfterSeconds;

    AnalysisEngineUnavailableException(String code, Integer retryAfterSeconds) {
        super("Analysis engine is temporarily unavailable: " + code);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
