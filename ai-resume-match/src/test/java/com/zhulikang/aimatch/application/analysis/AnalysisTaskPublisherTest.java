package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisOutboxEvent;
import com.zhulikang.aimatch.analysis.AnalysisOutboxEventType;
import com.zhulikang.aimatch.analysis.AnalysisOutboxRepository;
import com.zhulikang.aimatch.analysis.AnalysisOutboxStatus;
import com.zhulikang.aimatch.observability.RequestCorrelation;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AnalysisTaskPublisherTest {
    @Test
    void persistsAnalysisRequestedEvent() {
        AnalysisOutboxRepository outboxRepository = mock(AnalysisOutboxRepository.class);
        AnalysisTaskPublisher publisher = new AnalysisTaskPublisher(outboxRepository);

        publisher.publishAfterCommit(99L);

        ArgumentCaptor<AnalysisOutboxEvent> eventCaptor = ArgumentCaptor.forClass(AnalysisOutboxEvent.class);
        verify(outboxRepository).save(eventCaptor.capture());
        AnalysisOutboxEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(AnalysisOutboxEventType.ANALYSIS_REQUESTED);
        assertThat(event.getAggregateType()).isEqualTo("analysis_task");
        assertThat(event.getAggregateId()).isEqualTo(99L);
        assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.PENDING);
        assertThat(event.getPayloadJson()).contains("\"taskId\":99");
    }

    @Test
    void persistsCurrentCorrelationIdInOutboxPayload() {
        AnalysisOutboxRepository outboxRepository = mock(AnalysisOutboxRepository.class);
        AnalysisTaskPublisher publisher = new AnalysisTaskPublisher(outboxRepository);
        RequestCorrelation.put("request-1", "correlation-1");
        try {
            publisher.publishAfterCommit(99L);
        } finally {
            RequestCorrelation.clear();
        }

        ArgumentCaptor<AnalysisOutboxEvent> eventCaptor = ArgumentCaptor.forClass(AnalysisOutboxEvent.class);
        verify(outboxRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getPayloadJson())
            .contains("\"taskId\":99")
            .contains("\"correlationId\":\"correlation-1\"");
    }

    @Test
    void persistsCurrentTraceParentForDelayedOutboxPublication() {
        AnalysisOutboxRepository outboxRepository = mock(AnalysisOutboxRepository.class);
        AnalysisTaskPublisher publisher = new AnalysisTaskPublisher(outboxRepository);
        try (SdkTracerProvider provider = SdkTracerProvider.builder().build()) {
            Span span = provider.get("outbox-test").spanBuilder("submission").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                publisher.publishAfterCommit(99L);
            } finally {
                span.end();
            }

            ArgumentCaptor<AnalysisOutboxEvent> eventCaptor = ArgumentCaptor.forClass(
                AnalysisOutboxEvent.class
            );
            verify(outboxRepository).save(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getPayloadJson())
                .contains("\"traceparent\":\"00-" + span.getSpanContext().getTraceId())
                .contains(span.getSpanContext().getSpanId() + "-01\"");
        }
    }
}
