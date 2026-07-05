package com.zhulikang.aimatch.analysis;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface AnalysisOutboxRepository extends JpaRepository<AnalysisOutboxEvent, Long> {
    @Query("""
        select e from AnalysisOutboxEvent e
        where e.status in :statuses
          and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
        order by e.createdAt asc, e.id asc
        """)
    List<AnalysisOutboxEvent> findDueForPublish(
        @Param("statuses") Collection<AnalysisOutboxStatus> statuses,
        @Param("now") LocalDateTime now,
        Pageable pageable
    );
}
