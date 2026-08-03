package com.zhulikang.aimatch.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import com.zhulikang.aimatch.observability.RequestCorrelation;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CorrelationData.Confirm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class AnalysisOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(AnalysisOutboxPublisher.class);
    private static final List<AnalysisOutboxStatus> CLAIMABLE_STATUSES = List.of(
        AnalysisOutboxStatus.PENDING,
        AnalysisOutboxStatus.FAILED,
        AnalysisOutboxStatus.PROCESSING
    );

    private final AnalysisOutboxRepository outboxRepository;
    private final AnalysisTaskService taskService;
    private final RabbitTemplate rabbitTemplate;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final Duration confirmTimeout;
    private final Clock clock;
    private final AnalysisMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public AnalysisOutboxPublisher(
        AnalysisOutboxRepository outboxRepository,
        AnalysisTaskService taskService,
        RabbitTemplate rabbitTemplate,
        AnalysisMetrics metrics,
        @Value("${analysis.outbox.batch-size:20}") int batchSize,
        @Value("${analysis.outbox.max-attempts:10}") int maxAttempts,
        @Value("${analysis.outbox.retry-delay:30s}") Duration retryDelay,
        @Value("${analysis.outbox.confirm-timeout:5s}") Duration confirmTimeout
    ) {
        this(
            outboxRepository,
            taskService,
            rabbitTemplate,
            batchSize,
            maxAttempts,
            retryDelay,
            confirmTimeout,
            Clock.systemDefaultZone(),
            metrics
        );
    }

    AnalysisOutboxPublisher(
        AnalysisOutboxRepository outboxRepository,
        AnalysisTaskService taskService,
        RabbitTemplate rabbitTemplate,
        int batchSize,
        int maxAttempts,
        Duration retryDelay,
        Duration confirmTimeout,
        Clock clock,
        AnalysisMetrics metrics
    ) {
        this.outboxRepository = outboxRepository;
        this.taskService = taskService;
        this.rabbitTemplate = rabbitTemplate;
        if (batchSize < 1) {
            throw new IllegalArgumentException("analysis.outbox.batch-size must be positive");
        }
        this.batchSize = batchSize;
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("analysis.outbox.max-attempts must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.retryDelay = requirePositive(retryDelay, "analysis.outbox.retry-delay");
        this.confirmTimeout = requirePositive(confirmTimeout, "analysis.outbox.confirm-timeout");
        this.clock = clock;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${analysis.outbox.fixed-delay-ms:5000}")
    @Transactional
    public void publishPending() {
        LocalDateTime now = LocalDateTime.now(clock);
        finalizeExhaustedEvents(now);
        List<Long> eventIds = outboxRepository.findDueForPublishIds(
            CLAIMABLE_STATUSES,
            now,
            maxAttempts,
            PageRequest.of(0, batchSize)
        );
        for (Long eventId : eventIds) {
            int claimed = outboxRepository.markProcessingIfDue(
                eventId,
                CLAIMABLE_STATUSES,
                now,
                maxAttempts,
                AnalysisOutboxStatus.PROCESSING,
                now.plus(retryDelay)
            );
            if (claimed == 1) {
                Optional<AnalysisOutboxEvent> event = outboxRepository.findById(eventId);
                if (event.isPresent() && !publish(eventId, event.orElseThrow(), now)) {
                    break;
                }
            }
        }
    }

    private void finalizeExhaustedEvents(LocalDateTime now) {
        List<Long> eventIds = outboxRepository.findExhaustedNonTerminalIds(
            CLAIMABLE_STATUSES,
            maxAttempts,
            PageRequest.of(0, batchSize)
        );
        for (Long eventId : eventIds) {
            int finalized = outboxRepository.markDeadIfExhausted(
                eventId,
                CLAIMABLE_STATUSES,
                maxAttempts,
                AnalysisOutboxStatus.DEAD
            );
            if (finalized != 1) {
                continue;
            }
            outboxRepository.findById(eventId).ifPresent(event -> {
                event.sanitizeLastError();
                markPendingTaskDeliveryFailed(event, now);
                outboxRepository.save(event);
                metrics.outboxDead();
                log.error(
                    "Analysis outbox event {} entered DEAD during exhausted-event sweep after {} failed attempts: {}",
                    eventId,
                    event.getAttemptCount(),
                    event.getLastError()
                );
            });
        }
    }

    private boolean publish(Long eventId, AnalysisOutboxEvent event, LocalDateTime now) {
        try {
            Long taskId = extractTaskId(event);
            String correlationId = extractCorrelationId(event);
            publishToRabbit(eventId, taskId, correlationId);
            event.markPublished(now);
            metrics.outboxPublished();
        } catch (InterruptedException ex) {
            event.releaseAfterInterrupted(now);
            try {
                outboxRepository.save(event);
                log.warn("Analysis outbox publish interrupted; released event {} without consuming an attempt", eventId);
            } finally {
                Thread.currentThread().interrupt();
            }
            return false;
        } catch (Exception ex) {
            handlePublishFailure(eventId, event, ex, now);
        }
        outboxRepository.save(event);
        return true;
    }

    private void handlePublishFailure(
        Long eventId,
        AnalysisOutboxEvent event,
        Exception exception,
        LocalDateTime now
    ) {
        String message = errorMessage(exception);
        boolean dead = event.markPublishFailed(message, now.plus(retryDelay), maxAttempts);
        String storedError = event.getLastError();
        if (dead) {
            markPendingTaskDeliveryFailed(event, now);
            metrics.outboxDead();
            log.error(
                "Analysis outbox event {} entered DEAD after {} failed publish attempts: {}",
                eventId,
                event.getAttemptCount(),
                storedError
            );
            return;
        }
        metrics.outboxFailed();
        log.warn(
            "Failed to publish analysis outbox event {} (attempt {}/{}): {}",
            eventId,
            event.getAttemptCount(),
            maxAttempts,
            storedError
        );
    }

    private void markPendingTaskDeliveryFailed(AnalysisOutboxEvent event, LocalDateTime now) {
        if (event.getEventType() != AnalysisOutboxEventType.ANALYSIS_REQUESTED
            || !"analysis_task".equals(event.getAggregateType())) {
            return;
        }
        taskService.markDeliveryFailed(event.getAggregateId(), now);
    }

    private void publishToRabbit(Long eventId, Long taskId, String correlationId) throws Exception {
        CorrelationData correlationData = new CorrelationData("analysis-outbox-" + eventId);
        MessagePostProcessor headers = message -> {
            message.getMessageProperties().setHeader("analysisOutboxEventId", eventId);
            if (correlationId != null && !correlationId.isBlank()) {
                message.getMessageProperties().setHeader(RequestCorrelation.CORRELATION_ID_HEADER, correlationId);
                message.getMessageProperties().setCorrelationId(correlationId);
            }
            return message;
        };
        rabbitTemplate.convertAndSend(
            RabbitConfig.ANALYSIS_EXCHANGE,
            RabbitConfig.ANALYSIS_ROUTING_KEY,
            taskId,
            headers,
            correlationData
        );
        Confirm confirm = correlationData.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!confirm.isAck()) {
            throw new AmqpException("RabbitMQ broker did not confirm publish: " + confirm.getReason());
        }
        ReturnedMessage returned = correlationData.getReturned();
        if (returned != null) {
            throw new AmqpException(
                "RabbitMQ returned unroutable message: " + returned.getReplyText()
                    + " exchange=" + returned.getExchange()
                    + " routingKey=" + returned.getRoutingKey()
            );
        }
    }

    private Long extractTaskId(AnalysisOutboxEvent event) throws Exception {
        if (event.getEventType() != AnalysisOutboxEventType.ANALYSIS_REQUESTED) {
            throw new IllegalArgumentException("Unsupported analysis outbox event type: " + event.getEventType());
        }
        return objectMapper.readTree(event.getPayloadJson()).required("taskId").asLong();
    }

    private String extractCorrelationId(AnalysisOutboxEvent event) throws Exception {
        return objectMapper.readTree(event.getPayloadJson()).path("correlationId").asText(null);
    }

    private String errorMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private static Duration requirePositive(Duration value, String propertyName) {
        Duration candidate = Objects.requireNonNull(value, propertyName + " must not be null");
        if (candidate.isZero() || candidate.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
        return candidate;
    }
}
