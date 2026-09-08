package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.security.OwnerId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "analysis_submission_idempotency")
public class AnalysisSubmissionIdempotencyRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, columnDefinition = "char(64)")
    private String ownerId;

    @Column(nullable = false, length = 64, unique = true, columnDefinition = "char(64)")
    private String idempotencyKeyHash;

    @Column(nullable = false, length = 64, columnDefinition = "char(64)")
    private String requestFingerprint;

    @Column(unique = true)
    private Long taskId;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected AnalysisSubmissionIdempotencyRecord() {
    }

    public AnalysisSubmissionIdempotencyRecord(String idempotencyKeyHash, String requestFingerprint) {
        this(OwnerId.LEGACY, idempotencyKeyHash, requestFingerprint);
    }

    public AnalysisSubmissionIdempotencyRecord(
        String ownerId,
        String idempotencyKeyHash,
        String requestFingerprint
    ) {
        this.ownerId = OwnerId.requireValid(ownerId);
        this.idempotencyKeyHash = requireSha256(idempotencyKeyHash, "idempotencyKeyHash");
        this.requestFingerprint = requireSha256(requestFingerprint, "requestFingerprint");
    }

    public void complete(Long taskId) {
        if (this.taskId != null) {
            throw new IllegalStateException("Idempotency record is already complete");
        }
        this.taskId = Objects.requireNonNull(taskId, "taskId");
    }

    private String requireSha256(String value, String fieldName) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(fieldName + " must be a lowercase SHA-256 value");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getIdempotencyKeyHash() {
        return idempotencyKeyHash;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Long getTaskId() {
        return taskId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
