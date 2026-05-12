package com.zhulikang.aimatch.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, Long> {
    @Modifying
    @Query("""
        update AnalysisTask t
        set t.status = :running, t.updatedAt = :now
        where t.id = :taskId
          and (
            t.status = :pending
            or (t.status = :running and (:redelivered = true or t.updatedAt < :staleBefore))
          )
        """)
    int markRunningIfPendingOrStale(
        @Param("taskId") Long taskId,
        @Param("running") AnalysisTask.Status running,
        @Param("pending") AnalysisTask.Status pending,
        @Param("staleBefore") LocalDateTime staleBefore,
        @Param("now") LocalDateTime now,
        @Param("redelivered") boolean redelivered
    );

    @Modifying
    @Query("update AnalysisTask t set t.status = :status, t.updatedAt = :now where t.id = :taskId")
    int updateStatus(
        @Param("taskId") Long taskId,
        @Param("status") AnalysisTask.Status status,
        @Param("now") LocalDateTime now
    );
}
