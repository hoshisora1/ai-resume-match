package com.zhulikang.aimatch.resume;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ResumeRepository extends JpaRepository<Resume, Long> {
    boolean existsByIdAndOwnerId(Long id, String ownerId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Resume resume where resume.id = :id and resume.ownerId = :ownerId")
    int deleteOwnedById(@Param("id") Long id, @Param("ownerId") String ownerId);

    @Query("""
        select new com.zhulikang.aimatch.resume.ResumeDisplayView(resume.id, resume.fileName)
        from Resume resume
        where resume.id in :ids
        """)
    List<ResumeDisplayView> findDisplayViewsByIdIn(@Param("ids") Collection<Long> ids);

    @Query("""
        select new com.zhulikang.aimatch.resume.ResumeDisplayView(resume.id, resume.fileName)
        from Resume resume
        where resume.id in :ids and resume.ownerId = :ownerId
        """)
    List<ResumeDisplayView> findDisplayViewsByIdInAndOwnerId(
        @Param("ids") Collection<Long> ids,
        @Param("ownerId") String ownerId
    );

    @Query("""
        select new com.zhulikang.aimatch.resume.ResumeDisplayView(resume.id, resume.fileName)
        from Resume resume
        where resume.id = :id
        """)
    Optional<ResumeDisplayView> findDisplayViewById(@Param("id") Long id);

    @Query("""
        select new com.zhulikang.aimatch.resume.ResumeDisplayView(resume.id, resume.fileName)
        from Resume resume
        where resume.id = :id and resume.ownerId = :ownerId
        """)
    Optional<ResumeDisplayView> findDisplayViewByIdAndOwnerId(
        @Param("id") Long id,
        @Param("ownerId") String ownerId
    );
}
