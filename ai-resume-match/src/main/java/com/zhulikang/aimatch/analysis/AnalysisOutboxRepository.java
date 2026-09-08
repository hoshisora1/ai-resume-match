package com.zhulikang.aimatch.analysis;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AnalysisOutboxRepository extends JpaRepository<AnalysisOutboxEvent, Long> {
    long countByStatus(AnalysisOutboxStatus status);

    @Query("""
        select min(e.createdAt) from AnalysisOutboxEvent e
        where e.status in :statuses
        """)
    Optional<LocalDateTime> findOldestCreatedAtByStatuses(
        @Param("statuses") Collection<AnalysisOutboxStatus> statuses
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        delete from AnalysisOutboxEvent e
        where e.aggregateType = :aggregateType and e.aggregateId = :aggregateId
        """)
    int deleteByAggregate(
        @Param("aggregateType") String aggregateType,
        @Param("aggregateId") Long aggregateId
    );

    @Query("""
        select e.id from AnalysisOutboxEvent e
        where e.status in :statuses
          and e.attemptCount < :maxAttempts
          and (
            (e.status <> com.zhulikang.aimatch.analysis.AnalysisOutboxStatus.PROCESSING
              and (e.nextAttemptAt is null or e.nextAttemptAt <= :now))
            or
            (e.status = com.zhulikang.aimatch.analysis.AnalysisOutboxStatus.PROCESSING
              and (e.leaseUntil is null or e.leaseUntil <= :now))
          )
        order by e.createdAt asc, e.id asc
        """)
    List<Long> findDueForPublishIds(
        @Param("statuses") Collection<AnalysisOutboxStatus> statuses,
        @Param("now") LocalDateTime now,
        @Param("maxAttempts") int maxAttempts,
        Pageable pageable
    );

    @Query("""
        select e.id from AnalysisOutboxEvent e
        where e.status in :statuses
          and e.attemptCount >= :maxAttempts
        order by e.createdAt asc, e.id asc
        """)
    List<Long> findExhaustedNonTerminalIds(
        @Param("statuses") Collection<AnalysisOutboxStatus> statuses,
        @Param("maxAttempts") int maxAttempts,
        Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update AnalysisOutboxEvent e
        set e.status = :processing,
            e.nextAttemptAt = null,
            e.leaseToken = :leaseToken,
            e.leaseUntil = :leaseUntil
        where e.id = :eventId
          and e.status in :statuses
          and e.attemptCount < :maxAttempts
          and (
            (e.status <> :processing and (e.nextAttemptAt is null or e.nextAttemptAt <= :now))
            or
            (e.status = :processing and (e.leaseUntil is null or e.leaseUntil <= :now))
          )
        """)
    int markProcessingIfDue(
        @Param("eventId") Long eventId,
        @Param("statuses") Collection<AnalysisOutboxStatus> statuses,
        @Param("now") LocalDateTime now,
        @Param("maxAttempts") int maxAttempts,
        @Param("processing") AnalysisOutboxStatus processing,
        @Param("leaseToken") String leaseToken,
        @Param("leaseUntil") LocalDateTime leaseUntil
    );

    Optional<AnalysisOutboxEvent> findByIdAndStatusAndLeaseToken(
        Long id,
        AnalysisOutboxStatus status,
        String leaseToken
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update AnalysisOutboxEvent e
        set e.status = :dead,
            e.nextAttemptAt = null,
            e.leaseToken = null,
            e.leaseUntil = null,
            e.terminalAt = :terminalAt,
            e.publishedAt = null
        where e.id = :eventId
          and e.status in :statuses
          and e.attemptCount >= :maxAttempts
        """)
    int markDeadIfExhausted(
        @Param("eventId") Long eventId,
        @Param("statuses") Collection<AnalysisOutboxStatus> statuses,
        @Param("maxAttempts") int maxAttempts,
        @Param("dead") AnalysisOutboxStatus dead,
        @Param("terminalAt") LocalDateTime terminalAt
    );

    @Query("""
        select e.id from AnalysisOutboxEvent e
        where e.status in :statuses
          and e.terminalAt <= :cutoff
        order by e.terminalAt asc, e.id asc
        """)
    List<Long> findTerminalIdsBefore(
        @Param("statuses") Collection<AnalysisOutboxStatus> statuses,
        @Param("cutoff") LocalDateTime cutoff,
        Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        delete from AnalysisOutboxEvent e
        where e.id in :eventIds
          and e.status in :statuses
          and e.terminalAt <= :cutoff
        """)
    int deleteTerminalEvents(
        @Param("eventIds") Collection<Long> eventIds,
        @Param("statuses") Collection<AnalysisOutboxStatus> statuses,
        @Param("cutoff") LocalDateTime cutoff
    );
}
