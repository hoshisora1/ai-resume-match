package com.zhulikang.aimatch.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisSubmissionIdempotencyRepository
    extends JpaRepository<AnalysisSubmissionIdempotencyRecord, Long> {

    Optional<AnalysisSubmissionIdempotencyRecord> findByIdempotencyKeyHash(String idempotencyKeyHash);
}
