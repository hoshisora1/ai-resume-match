package com.zhulikang.aimatch.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MatchReportRepository extends JpaRepository<MatchReport, Long> {
    Optional<MatchReport> findByTaskId(Long taskId);

    @Query("""
        select new com.zhulikang.aimatch.analysis.MatchScoreView(report.taskId, report.matchScore)
        from MatchReport report
        where report.taskId in :taskIds
        """)
    List<MatchScoreView> findScoreViewsByTaskIdIn(@Param("taskIds") Collection<Long> taskIds);

    @Query("""
        select new com.zhulikang.aimatch.analysis.MatchScoreView(report.taskId, report.matchScore)
        from MatchReport report
        where report.taskId = :taskId
        """)
    Optional<MatchScoreView> findScoreViewByTaskId(@Param("taskId") Long taskId);

    @Query("select avg(report.matchScore) from MatchReport report")
    Double averageMatchScore();
}
