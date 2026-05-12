package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class DomainRepositoryTest {
    @Autowired
    ResumeRepository resumeRepository;
    @Autowired
    JobDescriptionRepository jobDescriptionRepository;
    @Autowired
    AnalysisTaskRepository analysisTaskRepository;
    @Autowired
    MatchReportRepository matchReportRepository;

    @Test
    void persistsResumeJobTaskAndReport() {
        Resume resume = resumeRepository.save(new Resume(
            "resume.docx",
            "Java Spring Boot Redis",
            "skills: Java, Redis"
        ));
        JobDescription job = jobDescriptionRepository.save(new JobDescription(
            "熟悉 Java Redis Kafka",
            "Java,Redis,Kafka"
        ));
        AnalysisTask task = analysisTaskRepository.save(new AnalysisTask(resume.getId(), job.getId()));
        MatchReport report = matchReportRepository.save(new MatchReport(task.getId(), 88, "匹配分数：88"));

        assertThat(task.getStatus()).isEqualTo(AnalysisTask.Status.PENDING);
        assertThat(report.getTaskId()).isEqualTo(task.getId());
        assertThat(matchReportRepository.findByTaskId(task.getId()))
            .contains(report);
    }

    @Test
    void taskStatusCanMoveThroughLifecycle() {
        AnalysisTask task = new AnalysisTask(1L, 2L);

        task.markRunning();
        assertThat(task.getStatus()).isEqualTo(AnalysisTask.Status.RUNNING);

        task.markSuccess();
        assertThat(task.getStatus()).isEqualTo(AnalysisTask.Status.SUCCESS);

        task.markFailed();
        assertThat(task.getStatus()).isEqualTo(AnalysisTask.Status.FAILED);
    }

    @Test
    void canReclaimStaleRunningTask() {
        AnalysisTask task = analysisTaskRepository.save(new AnalysisTask(1L, 2L));
        task.markRunning();
        ReflectionTestUtils.setField(task, "updatedAt", LocalDateTime.now().minusMinutes(30));
        analysisTaskRepository.saveAndFlush(task);

        int updated = analysisTaskRepository.markRunningIfPendingOrStale(
            task.getId(),
            AnalysisTask.Status.RUNNING,
            AnalysisTask.Status.PENDING,
            LocalDateTime.now().minusMinutes(15),
            LocalDateTime.now(),
            false
        );

        assertThat(updated).isEqualTo(1);
    }

    @Test
    void canReclaimRedeliveredRunningTaskEvenWhenFresh() {
        AnalysisTask task = analysisTaskRepository.save(new AnalysisTask(1L, 2L));
        task.markRunning();
        analysisTaskRepository.saveAndFlush(task);

        int updated = analysisTaskRepository.markRunningIfPendingOrStale(
            task.getId(),
            AnalysisTask.Status.RUNNING,
            AnalysisTask.Status.PENDING,
            LocalDateTime.now().minusMinutes(15),
            LocalDateTime.now(),
            true
        );

        assertThat(updated).isEqualTo(1);
    }
}
