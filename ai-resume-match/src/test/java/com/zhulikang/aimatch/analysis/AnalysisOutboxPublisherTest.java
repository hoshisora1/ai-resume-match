package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.observability.AnalysisMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CorrelationData.Confirm;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        when(repository.findDueForPublishIds(eq(CLAIMABLE_STATUSES), eq(now), eq(10), any(Pageable.class)))
            .thenReturn(List.of(10L));
        when(repository.markProcessingIfDue(
            10L,
            CLAIMABLE_STATUSES,
            now,
            10,
            AnalysisOutboxStatus.PROCESSING,
            now.plusSeconds(30)
        )).thenReturn(1);
        when(repository.findById(10L)).thenReturn(Optional.of(event));
        completePublishWithAck(rabbitTemplate);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AnalysisOutboxPublisher publisher = publisher(repository, rabbitTemplate, meterRegistry);

        publisher.publishPending();

        verify(rabbitTemplate).convertAndSend(
            eq(RabbitConfig.ANALYSIS_EXCHANGE),
            eq(RabbitConfig.ANALYSIS_ROUTING_KEY),
            eq(99L),
            any(MessagePostProcessor.class),
            any(CorrelationData.class)
        );
        verify(repository).save(event);
        assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(now);
        assertThat(event.getLastError()).isNull();
        assertThat(meterRegistry.counter("analysis.outbox.events", "outcome", "published").count()).isEqualTo(1.0);
    }

    @Test
    void skipsPublishWhenEventCannotBeClaimed() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(repository.findDueForPublishIds(eq(CLAIMABLE_STATUSES), eq(now), eq(10), any(Pageable.class)))
            .thenReturn(List.of(10L));
        when(repository.markProcessingIfDue(
            10L,
            CLAIMABLE_STATUSES,
            now,
            10,
            AnalysisOutboxStatus.PROCESSING,
            now.plusSeconds(30)
        )).thenReturn(0);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AnalysisOutboxPublisher publisher = publisher(repository, rabbitTemplate, meterRegistry);

        publisher.publishPending();

        verify(repository, never()).findById(10L);
        verify(rabbitTemplate, never())
            .convertAndSend(
                anyString(),
                anyString(),
                any(Long.class),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
            );
    }

    @Test
    void marksFailedPublishForRetry() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AnalysisOutboxEvent event = AnalysisOutboxEvent.analysisRequested(99L);
        when(repository.findDueForPublishIds(eq(CLAIMABLE_STATUSES), eq(now), eq(10), any(Pageable.class)))
            .thenReturn(List.of(10L));
        when(repository.markProcessingIfDue(
            10L,
            CLAIMABLE_STATUSES,
            now,
            10,
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
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
            );
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AnalysisOutboxPublisher publisher = publisher(repository, rabbitTemplate, meterRegistry);

        publisher.publishPending();

        verify(repository).save(event);
        assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).contains("down");
        assertThat(event.getNextAttemptAt()).isEqualTo(now.plusSeconds(30));
        assertThat(event.getPublishedAt()).isNull();
        assertThat(meterRegistry.counter("analysis.outbox.events", "outcome", "failed").count()).isEqualTo(1.0);
    }

    @Test
    void marksFailedPublishWhenBrokerNacksMessage() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AnalysisOutboxEvent event = AnalysisOutboxEvent.analysisRequested(99L);
        when(repository.findDueForPublishIds(eq(CLAIMABLE_STATUSES), eq(now), eq(10), any(Pageable.class)))
            .thenReturn(List.of(10L));
        when(repository.markProcessingIfDue(
            10L,
            CLAIMABLE_STATUSES,
            now,
            10,
            AnalysisOutboxStatus.PROCESSING,
            now.plusSeconds(30)
        )).thenReturn(1);
        when(repository.findById(10L)).thenReturn(Optional.of(event));
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new Confirm(false, "nacked"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
            eq(RabbitConfig.ANALYSIS_EXCHANGE),
            eq(RabbitConfig.ANALYSIS_ROUTING_KEY),
            eq(99L),
            any(MessagePostProcessor.class),
            any(CorrelationData.class)
        );
        AnalysisOutboxPublisher publisher = publisher(repository, rabbitTemplate);

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
        when(repository.findDueForPublishIds(eq(CLAIMABLE_STATUSES), eq(now), eq(10), any(Pageable.class)))
            .thenReturn(List.of(10L));
        when(repository.markProcessingIfDue(
            10L,
            CLAIMABLE_STATUSES,
            now,
            10,
            AnalysisOutboxStatus.PROCESSING,
            now.plusSeconds(30)
        )).thenReturn(1);
        when(repository.findById(10L)).thenReturn(Optional.of(event));
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
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
            any(MessagePostProcessor.class),
            any(CorrelationData.class)
        );
        AnalysisOutboxPublisher publisher = publisher(repository, rabbitTemplate);

        publisher.publishPending();

        assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.FAILED);
        assertThat(event.getLastError()).contains("returned").contains("NO_ROUTE");
        assertThat(event.getNextAttemptAt()).isEqualTo(now.plusSeconds(30));
    }

    @Test
    void publishesCorrelationIdAsRabbitHeader() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AnalysisOutboxEvent event = AnalysisOutboxEvent.analysisRequested(99L, "correlation-1");
        when(repository.findDueForPublishIds(eq(CLAIMABLE_STATUSES), eq(now), eq(10), any(Pageable.class)))
            .thenReturn(List.of(10L));
        when(repository.markProcessingIfDue(
            10L,
            CLAIMABLE_STATUSES,
            now,
            10,
            AnalysisOutboxStatus.PROCESSING,
            now.plusSeconds(30)
        )).thenReturn(1);
        when(repository.findById(10L)).thenReturn(Optional.of(event));
        completePublishWithAck(rabbitTemplate);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AnalysisOutboxPublisher publisher = publisher(repository, rabbitTemplate, meterRegistry);

        publisher.publishPending();

        ArgumentCaptor<MessagePostProcessor> processorCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(
            eq(RabbitConfig.ANALYSIS_EXCHANGE),
            eq(RabbitConfig.ANALYSIS_ROUTING_KEY),
            eq(99L),
            processorCaptor.capture(),
            any(CorrelationData.class)
        );
        Message message = new Message(new byte[0], new MessageProperties());
        Message processed = processorCaptor.getValue().postProcessMessage(message);
        String correlationId = processed.getMessageProperties().getHeader("X-Correlation-Id");
        Long eventId = processed.getMessageProperties().getHeader("analysisOutboxEventId");
        assertThat(correlationId).isEqualTo("correlation-1");
        assertThat(eventId).isEqualTo(10L);
    }

    @Test
    void movesEventToDeadAfterConfiguredMaximumFailures() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        AnalysisTaskService taskService = mock(AnalysisTaskService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AnalysisOutboxEvent event = AnalysisOutboxEvent.analysisRequested(99L);
        for (int attempt = 1; attempt < 10; attempt++) {
            assertThat(event.markPublishFailed("previous failure", now, 10)).isFalse();
        }
        when(repository.findDueForPublishIds(eq(CLAIMABLE_STATUSES), eq(now), eq(10), any(Pageable.class)))
            .thenReturn(List.of(10L));
        when(repository.markProcessingIfDue(
            10L,
            CLAIMABLE_STATUSES,
            now,
            10,
            AnalysisOutboxStatus.PROCESSING,
            now.plusSeconds(30)
        )).thenReturn(1);
        when(repository.findById(10L)).thenReturn(Optional.of(event));
        doThrow(new AmqpException("x".repeat(1_500)))
            .when(rabbitTemplate)
            .convertAndSend(
                eq(RabbitConfig.ANALYSIS_EXCHANGE),
                eq(RabbitConfig.ANALYSIS_ROUTING_KEY),
                eq(99L),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
            );
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AnalysisOutboxPublisher publisher = publisher(repository, taskService, rabbitTemplate, meterRegistry);

        publisher.publishPending();

        assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.DEAD);
        assertThat(event.getAttemptCount()).isEqualTo(10);
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getLastError()).hasSize(1_024);
        assertThat(meterRegistry.counter("analysis.outbox.events", "outcome", "dead").count())
            .isEqualTo(1.0);
        assertThat(meterRegistry.counter("analysis.outbox.events", "outcome", "failed").count())
            .isZero();
        verify(taskService).markDeliveryFailed(99L, now);
    }

    @Test
    void sweepsLegacyExhaustedEventsToDeadWithGuardedTaskTransition() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        AnalysisTaskService taskService = mock(AnalysisTaskService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AnalysisOutboxEvent event = AnalysisOutboxEvent.analysisRequested(101L);
        ReflectionTestUtils.setField(event, "status", AnalysisOutboxStatus.FAILED);
        ReflectionTestUtils.setField(event, "attemptCount", 10);
        ReflectionTestUtils.setField(event, "lastError", "broker down\r\nforged-entry\u202E");
        when(repository.findExhaustedNonTerminalIds(
            eq(CLAIMABLE_STATUSES),
            eq(10),
            any(Pageable.class)
        )).thenReturn(List.of(10L));
        when(repository.markDeadIfExhausted(
            10L,
            CLAIMABLE_STATUSES,
            10,
            AnalysisOutboxStatus.DEAD
        )).thenAnswer(invocation -> {
            ReflectionTestUtils.setField(event, "status", AnalysisOutboxStatus.DEAD);
            return 1;
        });
        when(repository.findById(10L)).thenReturn(Optional.of(event));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AnalysisOutboxPublisher publisher = publisher(repository, taskService, rabbitTemplate, meterRegistry);

        publisher.publishPending();

        assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.DEAD);
        assertThat(event.getLastError()).isEqualTo("broker down forged-entry");
        verify(taskService).markDeliveryFailed(101L, now);
        verify(repository).save(event);
        verify(rabbitTemplate, never()).convertAndSend(
            anyString(),
            anyString(),
            any(Long.class),
            any(MessagePostProcessor.class),
            any(CorrelationData.class)
        );
        assertThat(meterRegistry.counter("analysis.outbox.events", "outcome", "dead").count())
            .isEqualTo(1.0);
    }

    @Test
    void interruptionReleasesCurrentEventWithoutConsumingAttemptAndStopsBatch() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AnalysisOutboxEvent event = AnalysisOutboxEvent.analysisRequested(99L);
        ReflectionTestUtils.setField(event, "status", AnalysisOutboxStatus.PROCESSING);
        when(repository.findDueForPublishIds(eq(CLAIMABLE_STATUSES), eq(now), eq(10), any(Pageable.class)))
            .thenReturn(List.of(10L, 11L));
        when(repository.markProcessingIfDue(
            10L,
            CLAIMABLE_STATUSES,
            now,
            10,
            AnalysisOutboxStatus.PROCESSING,
            now.plusSeconds(30)
        )).thenReturn(1);
        when(repository.findById(10L)).thenReturn(Optional.of(event));
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(rabbitTemplate).convertAndSend(
            eq(RabbitConfig.ANALYSIS_EXCHANGE),
            eq(RabbitConfig.ANALYSIS_ROUTING_KEY),
            eq(99L),
            any(MessagePostProcessor.class),
            any(CorrelationData.class)
        );
        AnalysisOutboxPublisher publisher = publisher(repository, rabbitTemplate);

        try {
            publisher.publishPending();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.FAILED);
            assertThat(event.getAttemptCount()).isZero();
            assertThat(event.getNextAttemptAt()).isEqualTo(now);
            assertThat(event.getLastError()).isEqualTo("Outbox publish interrupted");
            verify(repository, never()).markProcessingIfDue(
                eq(11L),
                any(),
                any(),
                any(Integer.class),
                any(),
                any()
            );
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsInvalidRuntimeBounds() {
        assertThatThrownBy(() -> new AnalysisOutboxPublisher(
            mock(AnalysisOutboxRepository.class),
            mock(AnalysisTaskService.class),
            mock(RabbitTemplate.class),
            20,
            0,
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            clock,
            new AnalysisMetrics(new SimpleMeterRegistry())
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("max-attempts");

        assertThatThrownBy(() -> publisherWithBounds(0, 10, Duration.ofSeconds(30), Duration.ofSeconds(5)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("batch-size");
        assertThatThrownBy(() -> publisherWithBounds(20, 10, Duration.ZERO, Duration.ofSeconds(5)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("retry-delay");
        assertThatThrownBy(() -> publisherWithBounds(20, 10, Duration.ofSeconds(30), Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("confirm-timeout");
    }

    private void completePublishWithAck(RabbitTemplate rabbitTemplate) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
            eq(RabbitConfig.ANALYSIS_EXCHANGE),
            eq(RabbitConfig.ANALYSIS_ROUTING_KEY),
            eq(99L),
            any(MessagePostProcessor.class),
            any(CorrelationData.class)
        );
    }

    private AnalysisOutboxPublisher publisher(
        AnalysisOutboxRepository repository,
        RabbitTemplate rabbitTemplate
    ) {
        return publisher(repository, rabbitTemplate, new SimpleMeterRegistry());
    }

    private AnalysisOutboxPublisher publisher(
        AnalysisOutboxRepository repository,
        RabbitTemplate rabbitTemplate,
        SimpleMeterRegistry meterRegistry
    ) {
        return publisher(repository, mock(AnalysisTaskService.class), rabbitTemplate, meterRegistry);
    }

    private AnalysisOutboxPublisher publisher(
        AnalysisOutboxRepository repository,
        AnalysisTaskService taskService,
        RabbitTemplate rabbitTemplate,
        SimpleMeterRegistry meterRegistry
    ) {
        return new AnalysisOutboxPublisher(
            repository,
            taskService,
            rabbitTemplate,
            20,
            10,
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            clock,
            new AnalysisMetrics(meterRegistry)
        );
    }

    private AnalysisOutboxPublisher publisherWithBounds(
        int batchSize,
        int maxAttempts,
        Duration retryDelay,
        Duration confirmTimeout
    ) {
        return new AnalysisOutboxPublisher(
            mock(AnalysisOutboxRepository.class),
            mock(AnalysisTaskService.class),
            mock(RabbitTemplate.class),
            batchSize,
            maxAttempts,
            retryDelay,
            confirmTimeout,
            clock,
            new AnalysisMetrics(new SimpleMeterRegistry())
        );
    }
}
