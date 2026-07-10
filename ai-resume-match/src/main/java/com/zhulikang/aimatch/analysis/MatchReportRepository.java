package com.zhulikang.aimatch.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MatchReportRepository extends JpaRepository<MatchReport, Long> {
    Optional<MatchReport> findByTaskId(Long taskId);

    List<MatchReport> findAllByTaskIdIn(Collection<Long> taskIds);

    @Query("select avg(report.matchScore) from MatchReport report")
    Double averageMatchScore();
}
