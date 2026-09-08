package com.zhulikang.aimatch.application.analysis;

public class RateLimitUnavailableException extends RuntimeException {
    public RateLimitUnavailableException(Throwable cause) {
        super("Analysis request quota is temporarily unavailable", cause);
    }
}
