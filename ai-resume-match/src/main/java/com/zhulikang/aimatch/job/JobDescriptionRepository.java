package com.zhulikang.aimatch.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobDescriptionRepository extends JpaRepository<JobDescription, Long> {
    @Query("""
        select new com.zhulikang.aimatch.job.JobDescriptionDisplayView(job.id, job.title)
        from JobDescription job
        where job.id in :ids
        """)
    List<JobDescriptionDisplayView> findDisplayViewsByIdIn(@Param("ids") Collection<Long> ids);

    @Query("""
        select new com.zhulikang.aimatch.job.JobDescriptionDisplayView(job.id, job.title)
        from JobDescription job
        where job.id = :id
        """)
    Optional<JobDescriptionDisplayView> findDisplayViewById(@Param("id") Long id);
}
