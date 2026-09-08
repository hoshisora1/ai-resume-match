package com.zhulikang.aimatch.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;

import java.util.Map;

public final class TraceContextHeaders {
    public static final String TRACEPARENT_HEADER = "traceparent";

    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    private TraceContextHeaders() {
    }

    public static String captureCurrentTraceParent() {
        Map<String, String> carrier = new java.util.HashMap<>();
        PROPAGATOR.inject(Context.current(), carrier, Map::put);
        String traceParent = carrier.get(TRACEPARENT_HEADER);
        return validTraceParent(traceParent) ? traceParent : null;
    }

    public static Scope restore(String traceParent) {
        if (!validTraceParent(traceParent)) {
            return Context.current().makeCurrent();
        }
        Context extracted = PROPAGATOR.extract(
            Context.root(),
            Map.of(TRACEPARENT_HEADER, traceParent),
            MAP_GETTER
        );
        if (!Span.fromContext(extracted).getSpanContext().isValid()) {
            return Context.current().makeCurrent();
        }
        return extracted.makeCurrent();
    }

    public static boolean validTraceParent(String value) {
        if (value == null || !value.matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")) {
            return false;
        }
        return !value.regionMatches(3, "00000000000000000000000000000000", 0, 32)
            && !value.regionMatches(36, "0000000000000000", 0, 16);
    }
}
