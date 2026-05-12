package com.zhulikang.aimatch.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MatchReportRepository extends JpaRepository<MatchReport, Long> {
    Optional<MatchReport> findByTaskId(Long taskId);
}
