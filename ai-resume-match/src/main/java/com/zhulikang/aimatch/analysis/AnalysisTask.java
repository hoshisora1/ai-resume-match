package com.zhulikang.aimatch.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

@Entity
public class AnalysisTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long resumeId;

    @Column(nullable = false)
    private Long jobDescriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private int maxAttempts = 3;

    @Enumerated(EnumType.STRING)
    private AnalysisFailureCode failureCode;

    private String failureMessage;

    private LocalDateTime nextRetryAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public enum Status {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED_RETRYABLE,
        FAILED_FINAL,
        CANCELLED
    }

    protected AnalysisTask() {
    }

    public AnalysisTask(Long resumeId, Long jobDescriptionId) {
        this.resumeId = resumeId;
        this.jobDescriptionId = jobDescriptionId;
    }

    public void markRunning() {
        if (status != Status.PENDING && status != Status.RUNNING) {
            throw new IllegalStateException("Only pending or stale running analysis tasks can be started");
        }
        this.status = Status.RUNNING;
        this.attemptCount++;
        this.failureCode = null;
        this.failureMessage = null;
        this.nextRetryAt = null;
        this.startedAt = LocalDateTime.now();
        this.completedAt = null;
        this.updatedAt = LocalDateTime.now();
    }

    public void markSuccess() {
        this.status = Status.SUCCESS;
        this.failureCode = null;
        this.failureMessage = null;
        this.nextRetryAt = null;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void markRetryableFailure(AnalysisFailureCode failureCode, String failureMessage) {
        this.status = Status.FAILED_RETRYABLE;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void markFinalFailure(AnalysisFailureCode failureCode, String failureMessage) {
        this.status = Status.FAILED_FINAL;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.nextRetryAt = null;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed() {
        markFinalFailure(AnalysisFailureCode.UNEXPECTED_ERROR, "Analysis failed");
    }

    public void retry() {
        if (status != Status.FAILED_RETRYABLE) {
            throw new IllegalStateException("Only retryable failed analysis tasks can be retried");
        }
        this.status = Status.PENDING;
        this.failureCode = null;
        this.failureMessage = null;
        this.nextRetryAt = null;
        this.completedAt = null;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getResumeId() {
        return resumeId;
    }

    public Long getJobDescriptionId() {
        return jobDescriptionId;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public AnalysisFailureCode getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
