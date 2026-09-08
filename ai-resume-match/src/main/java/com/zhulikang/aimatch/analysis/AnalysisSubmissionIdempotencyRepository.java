package com.zhulikang.aimatch.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AnalysisSubmissionIdempotencyRepository
    extends JpaRepository<AnalysisSubmissionIdempotencyRecord, Long> {

    Optional<AnalysisSubmissionIdempotencyRecord> findByIdempotencyKeyHash(String idempotencyKeyHash);

    Optional<AnalysisSubmissionIdempotencyRecord> findByOwnerIdAndIdempotencyKeyHash(
        String ownerId,
        String idempotencyKeyHash
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AnalysisSubmissionIdempotencyRecord r where r.taskId = :taskId")
    int deleteByTaskId(@Param("taskId") Long taskId);

    @Query("""
        select r.id from AnalysisSubmissionIdempotencyRecord r
        where r.createdAt <= :cutoff
          and (
            r.taskId is null
            or r.taskId in (
              select t.id from AnalysisTask t where t.status in :terminalTaskStatuses
            )
          )
        order by r.createdAt asc, r.id asc
        """)
    List<Long> findIdsCreatedBefore(
        @Param("cutoff") LocalDateTime cutoff,
        @Param("terminalTaskStatuses") Collection<AnalysisTask.Status> terminalTaskStatuses,
        Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        delete from AnalysisSubmissionIdempotencyRecord r
        where r.id in :recordIds
          and r.createdAt <= :cutoff
          and (
            r.taskId is null
            or r.taskId in (
              select t.id from AnalysisTask t where t.status in :terminalTaskStatuses
            )
          )
        """)
    int deleteCreatedBefore(
        @Param("recordIds") Collection<Long> recordIds,
        @Param("cutoff") LocalDateTime cutoff,
        @Param("terminalTaskStatuses") Collection<AnalysisTask.Status> terminalTaskStatuses
    );
}
