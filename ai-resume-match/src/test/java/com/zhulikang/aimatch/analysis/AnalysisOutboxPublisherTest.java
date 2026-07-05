package com.zhulikang.aimatch.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisOutboxPublisherTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC);
    private final LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());

    @Test
    void publishesPendingEventsAndMarksThemPublished() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AnalysisOutboxEvent event = AnalysisOutboxEvent.analysisRequested(99L);
        when(repository.findDueForPublish(eq(List.of(AnalysisOutboxStatus.PENDING, AnalysisOutboxStatus.FAILED)), eq(now), any(Pageable.class)))
            .thenReturn(List.of(event));
        AnalysisOutboxPublisher publisher = new AnalysisOutboxPublisher(
            repository,
            rabbitTemplate,
            20,
            Duration.ofSeconds(30),
            clock
        );

        publisher.publishPending();

        verify(rabbitTemplate).convertAndSend(RabbitConfig.ANALYSIS_EXCHANGE, RabbitConfig.ANALYSIS_ROUTING_KEY, 99L);
        verify(repository).save(event);
        assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(now);
        assertThat(event.getLastError()).isNull();
    }

    @Test
    void marksFailedPublishForRetry() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AnalysisOutboxEvent event = AnalysisOutboxEvent.analysisRequested(99L);
        when(repository.findDueForPublish(eq(List.of(AnalysisOutboxStatus.PENDING, AnalysisOutboxStatus.FAILED)), eq(now), any(Pageable.class)))
            .thenReturn(List.of(event));
        doThrow(new AmqpException("down"))
            .when(rabbitTemplate)
            .convertAndSend(RabbitConfig.ANALYSIS_EXCHANGE, RabbitConfig.ANALYSIS_ROUTING_KEY, 99L);
        AnalysisOutboxPublisher publisher = new AnalysisOutboxPublisher(
            repository,
            rabbitTemplate,
            20,
            Duration.ofSeconds(30),
            clock
        );

        publisher.publishPending();

        verify(repository).save(event);
        assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).contains("down");
        assertThat(event.getNextAttemptAt()).isEqualTo(now.plusSeconds(30));
        assertThat(event.getPublishedAt()).isNull();
    }
}
