package com.zhulikang.aimatch.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextHeadersTest {
    @Test
    void capturesAndRestoresTheCurrentW3cTraceContext() {
        try (SdkTracerProvider provider = SdkTracerProvider.builder().build()) {
            Span source = provider.get("trace-context-test").spanBuilder("submission").startSpan();
            String traceParent;
            try (Scope ignored = source.makeCurrent()) {
                traceParent = TraceContextHeaders.captureCurrentTraceParent();
            } finally {
                source.end();
            }

            assertThat(traceParent)
                .matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$")
                .contains(source.getSpanContext().getTraceId())
                .contains(source.getSpanContext().getSpanId());

            try (Scope ignored = TraceContextHeaders.restore(traceParent)) {
                assertThat(Span.current().getSpanContext().getTraceId())
                    .isEqualTo(source.getSpanContext().getTraceId());
                assertThat(Span.current().getSpanContext().isRemote()).isTrue();
            }
        }
    }

    @Test
    void rejectsMalformedOrAllZeroTraceParents() {
        assertThat(TraceContextHeaders.validTraceParent(null)).isFalse();
        assertThat(TraceContextHeaders.validTraceParent("not-a-traceparent")).isFalse();
        assertThat(TraceContextHeaders.validTraceParent(
            "00-00000000000000000000000000000000-1111111111111111-01"
        )).isFalse();
        assertThat(TraceContextHeaders.validTraceParent(
            "00-11111111111111111111111111111111-0000000000000000-01"
        )).isFalse();
    }
}
