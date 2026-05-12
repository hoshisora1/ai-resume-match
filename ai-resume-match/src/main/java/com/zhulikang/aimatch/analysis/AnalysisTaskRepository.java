package com.zhulikang.aimatch.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, Long> {
    @Modifying
    @Query("update AnalysisTask t set t.status = :running where t.id = :taskId and t.status = :pending")
    int markRunningIfPending(
        @Param("taskId") Long taskId,
        @Param("running") AnalysisTask.Status running,
        @Param("pending") AnalysisTask.Status pending
    );

    @Modifying
    @Query("update AnalysisTask t set t.status = :status where t.id = :taskId")
    int updateStatus(@Param("taskId") Long taskId, @Param("status") AnalysisTask.Status status);
}
