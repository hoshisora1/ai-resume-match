package com.zhulikang.aimatch.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final RabbitTemplate rabbitTemplate;
    private final int batchSize;
    private final Duration retryDelay;
    private final Duration confirmTimeout;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public AnalysisOutboxPublisher(
        AnalysisOutboxRepository outboxRepository,
        RabbitTemplate rabbitTemplate,
        @Value("${analysis.outbox.batch-size:20}") int batchSize,
        @Value("${analysis.outbox.retry-delay:30s}") Duration retryDelay,
        @Value("${analysis.outbox.confirm-timeout:5s}") Duration confirmTimeout
    ) {
        this(outboxRepository, rabbitTemplate, batchSize, retryDelay, confirmTimeout, Clock.systemDefaultZone());
    }

    AnalysisOutboxPublisher(
        AnalysisOutboxRepository outboxRepository,
        RabbitTemplate rabbitTemplate,
        int batchSize,
        Duration retryDelay,
        Duration confirmTimeout,
        Clock clock
    ) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.batchSize = Math.max(1, batchSize);
        this.retryDelay = retryDelay;
        this.confirmTimeout = confirmTimeout;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${analysis.outbox.fixed-delay-ms:5000}")
    @Transactional
    public void publishPending() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> eventIds = outboxRepository.findDueForPublishIds(
            CLAIMABLE_STATUSES,
            now,
            PageRequest.of(0, batchSize)
        );
        for (Long eventId : eventIds) {
            int claimed = outboxRepository.markProcessingIfDue(
                eventId,
                CLAIMABLE_STATUSES,
                now,
                AnalysisOutboxStatus.PROCESSING,
                now.plus(retryDelay)
            );
            if (claimed == 1) {
                Optional<AnalysisOutboxEvent> event = outboxRepository.findById(eventId);
                event.ifPresent(outboxEvent -> publish(eventId, outboxEvent, now));
            }
        }
    }

    private void publish(Long eventId, AnalysisOutboxEvent event, LocalDateTime now) {
        try {
            Long taskId = extractTaskId(event);
            String correlationId = extractCorrelationId(event);
            publishToRabbit(eventId, taskId, correlationId);
            event.markPublished(now);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            event.markPublishFailed(errorMessage(ex), now.plus(retryDelay));
            log.warn("Interrupted while publishing analysis outbox event {}", eventId);
        } catch (Exception ex) {
            event.markPublishFailed(errorMessage(ex), now.plus(retryDelay));
            log.warn("Failed to publish analysis outbox event {}: {}", eventId, errorMessage(ex));
        }
        outboxRepository.save(event);
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
}
