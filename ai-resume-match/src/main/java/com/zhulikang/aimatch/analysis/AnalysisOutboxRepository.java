package com.zhulikang.aimatch.analysis;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface AnalysisOutboxRepository extends JpaRepository<AnalysisOutboxEvent, Long> {
    long countByStatus(AnalysisOutboxStatus status);

    @Query("""
        select e.id from AnalysisOutboxEvent e
        where e.status in :statuses
          and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
        order by e.createdAt asc, e.id asc
        """)
    List<Long> findDueForPublishIds(
        @Param("statuses") Collection<AnalysisOutboxStatus> statuses,
        @Param("now") LocalDateTime now,
        Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update AnalysisOutboxEvent e
        set e.status = :processing,
            e.nextAttemptAt = :nextAttemptAt
        where e.id = :eventId
          and e.status in :statuses
          and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
        """)
    int markProcessingIfDue(
        @Param("eventId") Long eventId,
        @Param("statuses") Collection<AnalysisOutboxStatus> statuses,
        @Param("now") LocalDateTime now,
        @Param("processing") AnalysisOutboxStatus processing,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt
    );
}
