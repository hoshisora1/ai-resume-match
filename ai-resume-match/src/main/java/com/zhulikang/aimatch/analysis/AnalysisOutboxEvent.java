package com.zhulikang.aimatch.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Entity
@Table(name = "analysis_outbox")
public class AnalysisOutboxEvent {
    private static final int MAX_ERROR_LENGTH = 1024;
    private static final String GENERIC_PUBLISH_ERROR = "Outbox publish failed";
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern REPEATED_WHITESPACE = Pattern.compile("\\s+");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private AnalysisOutboxEventType eventType;

    @Column(nullable = false, length = 64)
    private String aggregateType;

    @Column(nullable = false)
    private Long aggregateId;

    @Column(nullable = false, columnDefinition = "longtext")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AnalysisOutboxStatus status = AnalysisOutboxStatus.PENDING;

    @Column(nullable = false)
    private int attemptCount;

    private LocalDateTime nextAttemptAt;

    @Column(length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime publishedAt;

    protected AnalysisOutboxEvent() {
    }

    private AnalysisOutboxEvent(
        AnalysisOutboxEventType eventType,
        String aggregateType,
        Long aggregateId,
        String payloadJson
    ) {
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.payloadJson = payloadJson;
    }

    public static AnalysisOutboxEvent analysisRequested(Long taskId) {
        return analysisRequested(taskId, null);
    }

    public static AnalysisOutboxEvent analysisRequested(Long taskId, String correlationId) {
        return new AnalysisOutboxEvent(
            AnalysisOutboxEventType.ANALYSIS_REQUESTED,
            "analysis_task",
            taskId,
            payload(taskId, correlationId)
        );
    }

    private static String payload(Long taskId, String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return "{\"taskId\":" + taskId + "}";
        }
        return "{\"taskId\":" + taskId + ",\"correlationId\":\"" + escapeJson(correlationId) + "\"}";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public void markPublished(LocalDateTime now) {
        this.status = AnalysisOutboxStatus.PUBLISHED;
        this.lastError = null;
        this.nextAttemptAt = null;
        this.publishedAt = now;
    }

    public boolean markPublishFailed(
        String lastError,
        LocalDateTime nextAttemptAt,
        int maxAttempts
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (status == AnalysisOutboxStatus.PUBLISHED || status == AnalysisOutboxStatus.DEAD) {
            throw new IllegalStateException("Terminal outbox event cannot be failed again");
        }
        this.attemptCount++;
        this.lastError = sanitizeError(lastError);
        this.publishedAt = null;
        if (attemptCount >= maxAttempts) {
            this.status = AnalysisOutboxStatus.DEAD;
            this.nextAttemptAt = null;
            return true;
        }
        this.status = AnalysisOutboxStatus.FAILED;
        this.nextAttemptAt = nextAttemptAt;
        return false;
    }

    public void releaseAfterInterrupted(LocalDateTime now) {
        if (status != AnalysisOutboxStatus.PROCESSING) {
            throw new IllegalStateException("Only processing outbox events can be released");
        }
        this.status = AnalysisOutboxStatus.FAILED;
        this.lastError = sanitizeError("Outbox publish interrupted");
        this.nextAttemptAt = now;
        this.publishedAt = null;
    }

    public void sanitizeLastError() {
        this.lastError = sanitizeError(lastError);
    }

    static String sanitizeError(String value) {
        String candidate = value == null ? GENERIC_PUBLISH_ERROR : value;
        String cleaned = REPEATED_WHITESPACE.matcher(
            CONTROL_CHARACTERS.matcher(candidate).replaceAll(" ")
        ).replaceAll(" ").trim();
        if (cleaned.isEmpty()) {
            cleaned = GENERIC_PUBLISH_ERROR;
        }
        if (cleaned.length() <= MAX_ERROR_LENGTH) {
            return cleaned;
        }

        int end = MAX_ERROR_LENGTH;
        if (Character.isHighSurrogate(cleaned.charAt(end - 1))) {
            end--;
        }
        return cleaned.substring(0, end).stripTrailing();
    }

    public Long getId() {
        return id;
    }

    public AnalysisOutboxEventType getEventType() {
        return eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public AnalysisOutboxStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }
}
