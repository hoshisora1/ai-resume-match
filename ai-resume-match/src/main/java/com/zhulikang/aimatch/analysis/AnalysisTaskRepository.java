package com.zhulikang.aimatch.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, Long> {
    Page<AnalysisTask> findByStatus(AnalysisTask.Status status, Pageable pageable);

    long countByStatus(AnalysisTask.Status status);

    long countByStatusIn(Collection<AnalysisTask.Status> statuses);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update AnalysisTask t
        set t.status = :running,
            t.attemptCount = t.attemptCount + 1,
            t.failureCode = null,
            t.failureMessage = null,
            t.nextRetryAt = null,
            t.startedAt = :now,
            t.completedAt = null,
            t.updatedAt = :now
        where t.id = :taskId
          and (
            t.status = :pending
            or (t.status = :running and t.updatedAt < :staleBefore)
          )
        """)
    int markRunningIfPendingOrStale(
        @Param("taskId") Long taskId,
        @Param("running") AnalysisTask.Status running,
        @Param("pending") AnalysisTask.Status pending,
        @Param("staleBefore") LocalDateTime staleBefore,
        @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update AnalysisTask t
        set t.status = :status,
            t.failureCode = null,
            t.failureMessage = null,
            t.nextRetryAt = null,
            t.completedAt = :now,
            t.updatedAt = :now
        where t.id = :taskId
          and t.status = :running
        """)
    int markSuccess(
        @Param("taskId") Long taskId,
        @Param("status") AnalysisTask.Status status,
        @Param("running") AnalysisTask.Status running,
        @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update AnalysisTask t
        set t.status = :status,
            t.failureCode = :failureCode,
            t.failureMessage = :failureMessage,
            t.nextRetryAt = :nextRetryAt,
            t.completedAt = :now,
            t.updatedAt = :now
        where t.id = :taskId
          and t.status = :running
        """)
    int markFailure(
        @Param("taskId") Long taskId,
        @Param("status") AnalysisTask.Status status,
        @Param("running") AnalysisTask.Status running,
        @Param("failureCode") AnalysisFailureCode failureCode,
        @Param("failureMessage") String failureMessage,
        @Param("nextRetryAt") LocalDateTime nextRetryAt,
        @Param("now") LocalDateTime now
    );

    @Query("""
        select t from AnalysisTask t
        where t.status = :status
          and t.nextRetryAt <= :now
        order by t.nextRetryAt asc, t.id asc
        """)
    List<AnalysisTask> findDueRetryableTasks(
        @Param("status") AnalysisTask.Status status,
        @Param("now") LocalDateTime now,
        Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update AnalysisTask t
        set t.status = :pending,
            t.failureCode = null,
            t.failureMessage = null,
            t.nextRetryAt = null,
            t.completedAt = null,
            t.updatedAt = :now
        where t.id = :taskId
          and t.status = :retryable
        """)
    int markRetryableAsPending(
        @Param("taskId") Long taskId,
        @Param("pending") AnalysisTask.Status pending,
        @Param("retryable") AnalysisTask.Status retryable,
        @Param("now") LocalDateTime now
    );
}
