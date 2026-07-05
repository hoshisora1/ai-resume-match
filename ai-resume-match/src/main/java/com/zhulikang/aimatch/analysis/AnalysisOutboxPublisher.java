package com.zhulikang.aimatch.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class AnalysisOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(AnalysisOutboxPublisher.class);
    private static final List<AnalysisOutboxStatus> PUBLISHABLE_STATUSES = List.of(
        AnalysisOutboxStatus.PENDING,
        AnalysisOutboxStatus.FAILED
    );

    private final AnalysisOutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final int batchSize;
    private final Duration retryDelay;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnalysisOutboxPublisher(
        AnalysisOutboxRepository outboxRepository,
        RabbitTemplate rabbitTemplate,
        @Value("${analysis.outbox.batch-size:20}") int batchSize,
        @Value("${analysis.outbox.retry-delay:30s}") Duration retryDelay
    ) {
        this(outboxRepository, rabbitTemplate, batchSize, retryDelay, Clock.systemDefaultZone());
    }

    AnalysisOutboxPublisher(
        AnalysisOutboxRepository outboxRepository,
        RabbitTemplate rabbitTemplate,
        int batchSize,
        Duration retryDelay,
        Clock clock
    ) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.batchSize = Math.max(1, batchSize);
        this.retryDelay = retryDelay;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${analysis.outbox.fixed-delay:5s}")
    @Transactional
    public void publishPending() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<AnalysisOutboxEvent> events = outboxRepository.findDueForPublish(
            PUBLISHABLE_STATUSES,
            now,
            PageRequest.of(0, batchSize)
        );
        for (AnalysisOutboxEvent event : events) {
            publish(event, now);
        }
    }

    private void publish(AnalysisOutboxEvent event, LocalDateTime now) {
        try {
            Long taskId = extractTaskId(event);
            rabbitTemplate.convertAndSend(RabbitConfig.ANALYSIS_EXCHANGE, RabbitConfig.ANALYSIS_ROUTING_KEY, taskId);
            event.markPublished(now);
        } catch (Exception ex) {
            event.markPublishFailed(ex.getMessage(), now.plus(retryDelay));
            log.warn("Failed to publish analysis outbox event {}: {}", event.getId(), ex.getMessage());
        }
        outboxRepository.save(event);
    }

    private Long extractTaskId(AnalysisOutboxEvent event) throws Exception {
        if (event.getEventType() != AnalysisOutboxEventType.ANALYSIS_REQUESTED) {
            throw new IllegalArgumentException("Unsupported analysis outbox event type: " + event.getEventType());
        }
        return objectMapper.readTree(event.getPayloadJson()).required("taskId").asLong();
    }
}
