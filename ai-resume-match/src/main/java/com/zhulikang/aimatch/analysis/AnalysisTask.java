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
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum Status {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED
    }

    protected AnalysisTask() {
    }

    public AnalysisTask(Long resumeId, Long jobDescriptionId) {
        this.resumeId = resumeId;
        this.jobDescriptionId = jobDescriptionId;
    }

    public void markRunning() {
        this.status = Status.RUNNING;
    }

    public void markSuccess() {
        this.status = Status.SUCCESS;
    }

    public void markFailed() {
        this.status = Status.FAILED;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
