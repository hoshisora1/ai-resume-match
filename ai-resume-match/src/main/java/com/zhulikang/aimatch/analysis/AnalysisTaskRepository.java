package com.zhulikang.aimatch.analysis;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, Long> {
    Optional<AnalysisTask> findByIdAndOwnerId(Long id, String ownerId);

    boolean existsByIdAndOwnerId(Long id, String ownerId);

    Page<AnalysisTask> findByOwnerId(String ownerId, Pageable pageable);

    Page<AnalysisTask> findByOwnerIdAndStatus(String ownerId, AnalysisTask.Status status, Pageable pageable);

    long countByOwnerId(String ownerId);

    long countByOwnerIdAndStatus(String ownerId, AnalysisTask.Status status);

    long countByOwnerIdAndStatusIn(String ownerId, Collection<AnalysisTask.Status> statuses);

    boolean existsByResumeId(Long resumeId);

    boolean existsByJobDescriptionId(Long jobDescriptionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AnalysisTask t where t.id = :taskId and t.ownerId = :ownerId")
    Optional<AnalysisTask> findOwnedForDeletion(
        @Param("taskId") Long taskId,
        @Param("ownerId") String ownerId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AnalysisTask t where t.id = :taskId")
    Optional<AnalysisTask> findForDeletion(@Param("taskId") Long taskId);

    @Query("""
        select t.id from AnalysisTask t
        where t.createdAt <= :cutoff
        order by t.createdAt asc, t.id asc
        """)
    List<Long> findIdsCreatedBefore(
        @Param("cutoff") LocalDateTime cutoff,
        Pageable pageable
    );

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
          and t.attemptCount < t.maxAttempts
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
          and t.attemptCount = :expectedAttempt
        """)
    int markSuccess(
        @Param("taskId") Long taskId,
        @Param("status") AnalysisTask.Status status,
        @Param("running") AnalysisTask.Status running,
        @Param("expectedAttempt") int expectedAttempt,
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
          and t.attemptCount = :expectedAttempt
        """)
    int markFailure(
        @Param("taskId") Long taskId,
        @Param("status") AnalysisTask.Status status,
        @Param("running") AnalysisTask.Status running,
        @Param("expectedAttempt") int expectedAttempt,
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

    @Query("""
        select t.id from AnalysisTask t
        where t.status = :running
          and t.updatedAt < :staleBefore
        order by t.updatedAt asc, t.id asc
        """)
    List<Long> findStaleRunningTaskIds(
        @Param("running") AnalysisTask.Status running,
        @Param("staleBefore") LocalDateTime staleBefore,
        Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update AnalysisTask t
        set t.status = :pending,
            t.failureCode = null,
            t.failureMessage = null,
            t.nextRetryAt = null,
            t.startedAt = null,
            t.completedAt = null,
            t.updatedAt = :now
        where t.id = :taskId
          and t.status = :running
          and t.updatedAt < :staleBefore
          and t.attemptCount < t.maxAttempts
        """)
    int markStaleRunningAsPending(
        @Param("taskId") Long taskId,
        @Param("pending") AnalysisTask.Status pending,
        @Param("running") AnalysisTask.Status running,
        @Param("staleBefore") LocalDateTime staleBefore,
        @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update AnalysisTask t
        set t.status = :retryable,
            t.failureCode = :failureCode,
            t.failureMessage = :failureMessage,
            t.nextRetryAt = null,
            t.startedAt = null,
            t.completedAt = :now,
            t.updatedAt = :now
        where t.id = :taskId
          and t.status = :pending
        """)
    int markDeliveryFailedIfPending(
        @Param("taskId") Long taskId,
        @Param("pending") AnalysisTask.Status pending,
        @Param("retryable") AnalysisTask.Status retryable,
        @Param("failureCode") AnalysisFailureCode failureCode,
        @Param("failureMessage") String failureMessage,
        @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update AnalysisTask t
        set t.status = :failedFinal,
            t.failureCode = :failureCode,
            t.failureMessage = :failureMessage,
            t.nextRetryAt = null,
            t.completedAt = :now,
            t.updatedAt = :now
        where t.id = :taskId
          and t.status = :running
          and t.updatedAt < :staleBefore
          and t.attemptCount >= t.maxAttempts
        """)
    int markExhaustedStaleRunningAsFailedFinal(
        @Param("taskId") Long taskId,
        @Param("failedFinal") AnalysisTask.Status failedFinal,
        @Param("running") AnalysisTask.Status running,
        @Param("failureCode") AnalysisFailureCode failureCode,
        @Param("failureMessage") String failureMessage,
        @Param("staleBefore") LocalDateTime staleBefore,
        @Param("now") LocalDateTime now
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
