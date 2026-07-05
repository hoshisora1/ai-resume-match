package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.observability.RequestCorrelation;

public record ApiErrorResponse(String code, String message, String requestId) {
    public ApiErrorResponse(String code, String message) {
        this(code, message, RequestCorrelation.currentRequestId());
    }
}
