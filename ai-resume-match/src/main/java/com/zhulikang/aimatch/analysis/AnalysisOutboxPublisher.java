package com.zhulikang.aimatch.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhulikang.aimatch.config.AnalysisProperties;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import com.zhulikang.aimatch.observability.RequestCorrelation;
import com.zhulikang.aimatch.observability.TraceContextHeaders;
import io.opentelemetry.context.Scope;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CorrelationData.Confirm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
    private final OutboxRetryPolicy retryPolicy;
    private final Duration confirmTimeout;
    private final Duration leaseDuration;
    private final Clock clock;
    private final AnalysisMetrics metrics;
    private final TransactionOperations transactions;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public AnalysisOutboxPublisher(
        AnalysisOutboxRepository outboxRepository,
        AnalysisTaskService taskService,
        RabbitTemplate rabbitTemplate,
        AnalysisMetrics metrics,
        OutboxRetryPolicy retryPolicy,
        PlatformTransactionManager transactionManager,
        AnalysisProperties properties
    ) {
        this(
            outboxRepository,
            taskService,
            rabbitTemplate,
            properties.outbox().batchSize(),
            properties.outbox().maxAttempts(),
            retryPolicy,
            properties.outbox().confirmTimeout(),
            properties.outbox().leaseDuration(),
            Clock.systemDefaultZone(),
            metrics,
            requiresNewTransactions(transactionManager)
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
        this(
            outboxRepository,
            taskService,
            rabbitTemplate,
            batchSize,
            maxAttempts,
            fixedRetryPolicy(retryDelay),
            confirmTimeout,
            Duration.ofSeconds(30),
            clock,
            metrics,
            DIRECT_TRANSACTIONS
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
        Duration leaseDuration,
        Clock clock,
        AnalysisMetrics metrics,
        TransactionOperations transactions
    ) {
        this(
            outboxRepository,
            taskService,
            rabbitTemplate,
            batchSize,
            maxAttempts,
            fixedRetryPolicy(retryDelay),
            confirmTimeout,
            leaseDuration,
            clock,
            metrics,
            transactions
        );
    }

    AnalysisOutboxPublisher(
        AnalysisOutboxRepository outboxRepository,
        AnalysisTaskService taskService,
        RabbitTemplate rabbitTemplate,
        int batchSize,
        int maxAttempts,
        OutboxRetryPolicy retryPolicy,
        Duration confirmTimeout,
        Duration leaseDuration,
        Clock clock,
        AnalysisMetrics metrics,
        TransactionOperations transactions
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
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.confirmTimeout = requirePositive(confirmTimeout, "analysis.outbox.confirm-timeout");
        this.leaseDuration = requirePositive(leaseDuration, "analysis.outbox.lease-duration");
        if (this.leaseDuration.compareTo(this.confirmTimeout) <= 0) {
            throw new IllegalArgumentException(
                "analysis.outbox.lease-duration must be greater than confirm-timeout"
            );
        }
        this.clock = clock;
        this.metrics = metrics;
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    @Scheduled(fixedDelayString = "${analysis.outbox.fixed-delay-ms:5000}")
    public void publishPending() {
        LocalDateTime scanTime = LocalDateTime.now(clock);
        finalizeExhaustedEvents(scanTime);
        for (Long eventId : findDueEventIds(scanTime)) {
            Optional<ClaimedOutboxEvent> event = claimEvent(
                eventId,
                LocalDateTime.now(clock)
            );
            if (event.isPresent() && !publish(event.orElseThrow())) {
                break;
            }
        }
    }

    private List<Long> findDueEventIds(LocalDateTime now) {
        List<Long> eventIds = transactions.execute(status ->
            outboxRepository.findDueForPublishIds(
                CLAIMABLE_STATUSES,
                now,
                maxAttempts,
                PageRequest.of(0, batchSize)
            )
        );
        return eventIds == null ? List.of() : eventIds;
    }

    private Optional<ClaimedOutboxEvent> claimEvent(Long eventId, LocalDateTime now) {
        Optional<ClaimedOutboxEvent> claimed = transactions.execute(status -> {
            String leaseToken = UUID.randomUUID().toString();
            int updated = outboxRepository.markProcessingIfDue(
                eventId,
                CLAIMABLE_STATUSES,
                now,
                maxAttempts,
                AnalysisOutboxStatus.PROCESSING,
                leaseToken,
                now.plus(leaseDuration)
            );
            if (updated != 1) {
                return Optional.empty();
            }
            return outboxRepository.findById(eventId)
                .map(event -> ClaimedOutboxEvent.from(eventId, event, leaseToken));
        });
        return claimed == null ? Optional.empty() : claimed;
    }

    private void finalizeExhaustedEvents(LocalDateTime now) {
        transactions.executeWithoutResult(status -> {
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
                    AnalysisOutboxStatus.DEAD,
                    now
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
        });
    }

    private boolean publish(ClaimedOutboxEvent event) {
        try {
            Long taskId = extractTaskId(event);
            String correlationId = extractCorrelationId(event);
            String traceParent = extractTraceParent(event);
            publishToRabbit(event.id(), taskId, correlationId, traceParent);
            afterBrokerAck(event.id());
            boolean finalized = Boolean.TRUE.equals(transactions.execute(status ->
                outboxRepository.findByIdAndStatusAndLeaseToken(
                    event.id(),
                    AnalysisOutboxStatus.PROCESSING,
                    event.leaseToken()
                ).map(leased -> {
                    leased.markPublished(LocalDateTime.now(clock));
                    outboxRepository.save(leased);
                    return true;
                }).orElse(false)
            ));
            if (finalized) {
                metrics.outboxPublished();
            } else {
                log.warn("Ignored stale outbox publish completion for event {}", event.id());
            }
        } catch (InterruptedException ex) {
            try {
                boolean released = Boolean.TRUE.equals(transactions.execute(status ->
                    outboxRepository.findByIdAndStatusAndLeaseToken(
                        event.id(),
                        AnalysisOutboxStatus.PROCESSING,
                        event.leaseToken()
                    ).map(leased -> {
                        leased.releaseAfterInterrupted(LocalDateTime.now(clock));
                        outboxRepository.save(leased);
                        return true;
                    }).orElse(false)
                ));
                if (released) {
                    log.warn(
                        "Analysis outbox publish interrupted; released event {} without consuming an attempt",
                        event.id()
                    );
                } else {
                    log.warn("Ignored stale interrupted outbox publish for event {}", event.id());
                }
            } finally {
                Thread.currentThread().interrupt();
            }
            return false;
        } catch (Exception ex) {
            handlePublishFailure(event, ex, LocalDateTime.now(clock));
        }
        return true;
    }

    /**
     * Package-level lifecycle seam used to exercise the broker-ack/database-commit crash window.
     * Production publishing has no work between the confirm and the durable outbox transition.
     */
    void afterBrokerAck(Long eventId) {
        // Intentionally empty.
    }

    private void handlePublishFailure(
        ClaimedOutboxEvent claimed,
        Exception exception,
        LocalDateTime now
    ) {
        String message = errorMessage(exception);
        PublishFailure failure = transactions.execute(status ->
            outboxRepository.findByIdAndStatusAndLeaseToken(
                claimed.id(),
                AnalysisOutboxStatus.PROCESSING,
                claimed.leaseToken()
            ).map(event -> {
                Duration retryDelay = retryPolicy.delayForAttempt(event.getAttemptCount() + 1);
                boolean dead = event.markPublishFailed(
                    message,
                    now,
                    now.plus(retryDelay),
                    maxAttempts
                );
                if (dead) {
                    markPendingTaskDeliveryFailed(event, now);
                }
                outboxRepository.save(event);
                return new PublishFailure(dead, event.getAttemptCount(), event.getLastError());
            }).orElse(null)
        );
        if (failure == null) {
            log.warn("Ignored stale outbox publish failure for event {}", claimed.id());
            return;
        }
        if (failure.dead()) {
            metrics.outboxDead();
            log.error(
                "Analysis outbox event {} entered DEAD after {} failed publish attempts: {}",
                claimed.id(),
                failure.attemptCount(),
                failure.lastError()
            );
            return;
        }
        metrics.outboxFailed();
        log.warn(
            "Failed to publish analysis outbox event {} (attempt {}/{}): {}",
            claimed.id(),
            failure.attemptCount(),
            maxAttempts,
            failure.lastError()
        );
    }

    private void markPendingTaskDeliveryFailed(AnalysisOutboxEvent event, LocalDateTime now) {
        if (event.getEventType() != AnalysisOutboxEventType.ANALYSIS_REQUESTED
            || !"analysis_task".equals(event.getAggregateType())) {
            return;
        }
        taskService.markDeliveryFailed(event.getAggregateId(), now);
    }

    private void publishToRabbit(
        Long eventId,
        Long taskId,
        String correlationId,
        String traceParent
    ) throws Exception {
        CorrelationData correlationData = new CorrelationData("analysis-outbox-" + eventId);
        MessagePostProcessor headers = message -> {
            message.getMessageProperties().setHeader("analysisOutboxEventId", eventId);
            if (correlationId != null && !correlationId.isBlank()) {
                message.getMessageProperties().setHeader(RequestCorrelation.CORRELATION_ID_HEADER, correlationId);
                message.getMessageProperties().setCorrelationId(correlationId);
            }
            if (TraceContextHeaders.validTraceParent(traceParent)) {
                message.getMessageProperties().setHeader(
                    TraceContextHeaders.TRACEPARENT_HEADER,
                    traceParent
                );
            }
            return message;
        };
        try (Scope ignored = TraceContextHeaders.restore(traceParent)) {
            rabbitTemplate.convertAndSend(
                RabbitConfig.ANALYSIS_EXCHANGE,
                RabbitConfig.ANALYSIS_ROUTING_KEY,
                taskId,
                headers,
                correlationData
            );
            Confirm confirm = correlationData.getFuture().get(
                confirmTimeout.toMillis(),
                TimeUnit.MILLISECONDS
            );
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
    }

    private Long extractTaskId(ClaimedOutboxEvent event) throws Exception {
        if (event.eventType() != AnalysisOutboxEventType.ANALYSIS_REQUESTED) {
            throw new IllegalArgumentException("Unsupported analysis outbox event type: " + event.eventType());
        }
        return objectMapper.readTree(event.payloadJson()).required("taskId").asLong();
    }

    private String extractCorrelationId(ClaimedOutboxEvent event) throws Exception {
        return objectMapper.readTree(event.payloadJson()).path("correlationId").asText(null);
    }

    private String extractTraceParent(ClaimedOutboxEvent event) throws Exception {
        return objectMapper.readTree(event.payloadJson()).path("traceparent").asText(null);
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

    private static TransactionOperations requiresNewTransactions(
        PlatformTransactionManager transactionManager
    ) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private static OutboxRetryPolicy fixedRetryPolicy(Duration retryDelay) {
        return new OutboxRetryPolicy(retryDelay, retryDelay, 0.0, () -> 0.5);
    }

    private record ClaimedOutboxEvent(
        Long id,
        String leaseToken,
        AnalysisOutboxEventType eventType,
        String aggregateType,
        Long aggregateId,
        String payloadJson
    ) {
        private static ClaimedOutboxEvent from(
            Long eventId,
            AnalysisOutboxEvent event,
            String leaseToken
        ) {
            return new ClaimedOutboxEvent(
                eventId,
                leaseToken,
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getPayloadJson()
            );
        }
    }

    private record PublishFailure(boolean dead, int attemptCount, String lastError) {
    }

    private static final TransactionOperations DIRECT_TRANSACTIONS = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    };
}
