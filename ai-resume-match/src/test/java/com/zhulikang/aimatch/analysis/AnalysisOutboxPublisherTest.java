package com.zhulikang.aimatch.analysis;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CorrelationData.Confirm;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisOutboxPublisherTest {
    private static final List<AnalysisOutboxStatus> CLAIMABLE_STATUSES = List.of(
        AnalysisOutboxStatus.PENDING,
        AnalysisOutboxStatus.FAILED,
        AnalysisOutboxStatus.PROCESSING
    );

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC);
    private final LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());

    @Test
    void publishesPendingEventsAndMarksThemPublished() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AnalysisOutboxEvent event = AnalysisOutboxEvent.analysisRequested(99L);
        when(repository.findDueForPublishIds(eq(CLAIMABLE_STATUSES), eq(now), any(Pageable.class)))
            .thenReturn(List.of(10L));
        when(repository.markProcessingIfDue(
            10L,
            CLAIMABLE_STATUSES,
            now,
            AnalysisOutboxStatus.PROCESSING,
            now.plusSeconds(30)
        )).thenReturn(1);
        when(repository.findById(10L)).thenReturn(Optional.of(event));
        completePublishWithAck(rabbitTemplate);
        AnalysisOutboxPublisher publisher = new AnalysisOutboxPublisher(
            repository,
            rabbitTemplate,
            20,
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            clock
        );

        publisher.publishPending();

        verify(rabbitTemplate).convertAndSend(
            eq(RabbitConfig.ANALYSIS_EXCHANGE),
            eq(RabbitConfig.ANALYSIS_ROUTING_KEY),
            eq(99L),
            any(CorrelationData.class)
        );
        verify(repository).save(event);
        assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(now);
        assertThat(event.getLastError()).isNull();
    }

    @Test
    void skipsPublishWhenEventCannotBeClaimed() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(repository.findDueForPublishIds(eq(CLAIMABLE_STATUSES), eq(now), any(Pageable.class)))
            .thenReturn(List.of(10L));
        when(repository.markProcessingIfDue(
            10L,
            CLAIMABLE_STATUSES,
            now,
            AnalysisOutboxStatus.PROCESSING,
            now.plusSeconds(30)
        )).thenReturn(0);
        AnalysisOutboxPublisher publisher = new AnalysisOutboxPublisher(
            repository,
            rabbitTemplate,
            20,
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            clock
        );

        publisher.publishPending();

        verify(repository, never()).findById(10L);
        verify(rabbitTemplate, never())
            .convertAndSend(anyString(), anyString(), any(Long.class), any(CorrelationData.class));
    }

    @Test
    void marksFailedPublishForRetry() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AnalysisOutboxEvent event = AnalysisOutboxEvent.analysisRequested(99L);
        when(repository.findDueForPublishIds(eq(CLAIMABLE_STATUSES), eq(now), any(Pageable.class)))
            .thenReturn(List.of(10L));
        when(repository.markProcessingIfDue(
            10L,
            CLAIMABLE_STATUSES,
            now,
            AnalysisOutboxStatus.PROCESSING,
            now.plusSeconds(30)
        )).thenReturn(1);
        when(repository.findById(10L)).thenReturn(Optional.of(event));
        doThrow(new AmqpException("down"))
            .when(rabbitTemplate)
            .convertAndSend(
                eq(RabbitConfig.ANALYSIS_EXCHANGE),
                eq(RabbitConfig.ANALYSIS_ROUTING_KEY),
                eq(99L),
                any(CorrelationData.class)
            );
        AnalysisOutboxPublisher publisher = new AnalysisOutboxPublisher(
            repository,
            rabbitTemplate,
            20,
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
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

    @Test
    void marksFailedPublishWhenBrokerNacksMessage() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AnalysisOutboxEvent event = AnalysisOutboxEvent.analysisRequested(99L);
        when(repository.findDueForPublishIds(eq(CLAIMABLE_STATUSES), eq(now), any(Pageable.class)))
            .thenReturn(List.of(10L));
        when(repository.markProcessingIfDue(
            10L,
            CLAIMABLE_STATUSES,
            now,
            AnalysisOutboxStatus.PROCESSING,
            now.plusSeconds(30)
        )).thenReturn(1);
        when(repository.findById(10L)).thenReturn(Optional.of(event));
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new Confirm(false, "nacked"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
            eq(RabbitConfig.ANALYSIS_EXCHANGE),
            eq(RabbitConfig.ANALYSIS_ROUTING_KEY),
            eq(99L),
            any(CorrelationData.class)
        );
        AnalysisOutboxPublisher publisher = new AnalysisOutboxPublisher(
            repository,
            rabbitTemplate,
            20,
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            clock
        );

        publisher.publishPending();

        assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.FAILED);
        assertThat(event.getLastError()).contains("nacked");
        assertThat(event.getNextAttemptAt()).isEqualTo(now.plusSeconds(30));
    }

    @Test
    void marksFailedPublishWhenMessageIsReturnedAsUnroutable() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AnalysisOutboxEvent event = AnalysisOutboxEvent.analysisRequested(99L);
        when(repository.findDueForPublishIds(eq(CLAIMABLE_STATUSES), eq(now), any(Pageable.class)))
            .thenReturn(List.of(10L));
        when(repository.markProcessingIfDue(
            10L,
            CLAIMABLE_STATUSES,
            now,
            AnalysisOutboxStatus.PROCESSING,
            now.plusSeconds(30)
        )).thenReturn(1);
        when(repository.findById(10L)).thenReturn(Optional.of(event));
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.setReturned(new ReturnedMessage(
                new Message(new byte[0], new MessageProperties()),
                312,
                "NO_ROUTE",
                RabbitConfig.ANALYSIS_EXCHANGE,
                RabbitConfig.ANALYSIS_ROUTING_KEY
            ));
            correlationData.getFuture().complete(new Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
            eq(RabbitConfig.ANALYSIS_EXCHANGE),
            eq(RabbitConfig.ANALYSIS_ROUTING_KEY),
            eq(99L),
            any(CorrelationData.class)
        );
        AnalysisOutboxPublisher publisher = new AnalysisOutboxPublisher(
            repository,
            rabbitTemplate,
            20,
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            clock
        );

        publisher.publishPending();

        assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.FAILED);
        assertThat(event.getLastError()).contains("returned").contains("NO_ROUTE");
        assertThat(event.getNextAttemptAt()).isEqualTo(now.plusSeconds(30));
    }

    private void completePublishWithAck(RabbitTemplate rabbitTemplate) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
            eq(RabbitConfig.ANALYSIS_EXCHANGE),
            eq(RabbitConfig.ANALYSIS_ROUTING_KEY),
            eq(99L),
            any(CorrelationData.class)
        );
    }
}
