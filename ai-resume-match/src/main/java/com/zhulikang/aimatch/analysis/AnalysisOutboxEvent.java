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

@Entity
@Table(name = "analysis_outbox")
public class AnalysisOutboxEvent {
    private static final int MAX_ERROR_LENGTH = 1024;

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
        return new AnalysisOutboxEvent(
            AnalysisOutboxEventType.ANALYSIS_REQUESTED,
            "analysis_task",
            taskId,
            "{\"taskId\":" + taskId + "}"
        );
    }

    public void markPublished(LocalDateTime now) {
        this.status = AnalysisOutboxStatus.PUBLISHED;
        this.lastError = null;
        this.nextAttemptAt = null;
        this.publishedAt = now;
    }

    public void markPublishFailed(String lastError, LocalDateTime nextAttemptAt) {
        this.status = AnalysisOutboxStatus.FAILED;
        this.attemptCount++;
        this.lastError = truncate(lastError);
        this.nextAttemptAt = nextAttemptAt;
        this.publishedAt = null;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
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
