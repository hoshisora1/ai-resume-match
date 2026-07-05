package com.zhulikang.aimatch.observability;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

public final class RequestCorrelation {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String REQUEST_ID_MDC_KEY = "requestId";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
    public static final String REQUEST_ID_ATTRIBUTE =
        RequestCorrelation.class.getName() + ".requestId";
    public static final String CORRELATION_ID_ATTRIBUTE =
        RequestCorrelation.class.getName() + ".correlationId";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private RequestCorrelation() {
    }

    public static String safeOrNew(String candidate) {
        if (candidate != null && SAFE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }

    public static String currentRequestId() {
        return MDC.get(REQUEST_ID_MDC_KEY);
    }

    public static String currentCorrelationId() {
        return MDC.get(CORRELATION_ID_MDC_KEY);
    }

    public static String currentCorrelationIdOrNew() {
        String correlationId = currentCorrelationId();
        return correlationId == null || correlationId.isBlank() ? UUID.randomUUID().toString() : correlationId;
    }

    public static void put(String requestId, String correlationId) {
        if (requestId != null && !requestId.isBlank()) {
            MDC.put(REQUEST_ID_MDC_KEY, requestId);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        }
    }

    public static void clear() {
        MDC.remove(REQUEST_ID_MDC_KEY);
        MDC.remove(CORRELATION_ID_MDC_KEY);
    }
}
