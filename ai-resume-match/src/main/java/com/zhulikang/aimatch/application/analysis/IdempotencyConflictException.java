package com.zhulikang.aimatch.application.analysis;

public class IdempotencyConflictException extends RuntimeException {
    public static final String MESSAGE = "Idempotency-Key was already used for a different request";

    public IdempotencyConflictException() {
        super(MESSAGE);
    }
}
