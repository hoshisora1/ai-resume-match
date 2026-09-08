package com.zhulikang.aimatch.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobDescriptionRepository extends JpaRepository<JobDescription, Long> {
    boolean existsByIdAndOwnerId(Long id, String ownerId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from JobDescription job where job.id = :id and job.ownerId = :ownerId")
    int deleteOwnedById(@Param("id") Long id, @Param("ownerId") String ownerId);

    @Query("""
        select new com.zhulikang.aimatch.job.JobDescriptionDisplayView(job.id, job.title)
        from JobDescription job
        where job.id in :ids
        """)
    List<JobDescriptionDisplayView> findDisplayViewsByIdIn(@Param("ids") Collection<Long> ids);

    @Query("""
        select new com.zhulikang.aimatch.job.JobDescriptionDisplayView(job.id, job.title)
        from JobDescription job
        where job.id in :ids and job.ownerId = :ownerId
        """)
    List<JobDescriptionDisplayView> findDisplayViewsByIdInAndOwnerId(
        @Param("ids") Collection<Long> ids,
        @Param("ownerId") String ownerId
    );

    @Query("""
        select new com.zhulikang.aimatch.job.JobDescriptionDisplayView(job.id, job.title)
        from JobDescription job
        where job.id = :id
        """)
    Optional<JobDescriptionDisplayView> findDisplayViewById(@Param("id") Long id);

    @Query("""
        select new com.zhulikang.aimatch.job.JobDescriptionDisplayView(job.id, job.title)
        from JobDescription job
        where job.id = :id and job.ownerId = :ownerId
        """)
    Optional<JobDescriptionDisplayView> findDisplayViewByIdAndOwnerId(
        @Param("id") Long id,
        @Param("ownerId") String ownerId
    );
}
