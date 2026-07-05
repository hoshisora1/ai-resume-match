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
    }

    @Test
    void taskCanMoveFromRunningToFinalFailure() {
        AnalysisTask task = new AnalysisTask(1L, 2L);

        task.markRunning();
        task.markFinalFailure(AnalysisFailureCode.UNEXPECTED_ERROR, "Analysis failed");

        assertThat(task.getStatus()).isEqualTo(AnalysisTask.Status.FAILED_FINAL);
        assertThat(task.getFailureCode()).isEqualTo(AnalysisFailureCode.UNEXPECTED_ERROR);
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
        AnalysisTask updatedTask = analysisTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(updatedTask.getAttemptCount()).isEqualTo(2);
        assertThat(updatedTask.getStartedAt()).isNotNull();
    }

    @Test
    void markSuccessOnlyUpdatesRunningTaskAndClearsFailureMetadata() {
        AnalysisTask task = new AnalysisTask(1L, 2L);
        task.markRunning();
        task.markRetryableFailure(AnalysisFailureCode.AI_UNAVAILABLE, "AI unavailable");
        task.retry();
        task.markRunning();
        task = analysisTaskRepository.saveAndFlush(task);

        int updated = analysisTaskRepository.markSuccess(
            task.getId(),
            AnalysisTask.Status.SUCCESS,
            AnalysisTask.Status.RUNNING,
            LocalDateTime.now()
        );

        assertThat(updated).isEqualTo(1);
        AnalysisTask updatedTask = analysisTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(updatedTask.getStatus()).isEqualTo(AnalysisTask.Status.SUCCESS);
        assertThat(updatedTask.getFailureCode()).isNull();
        assertThat(updatedTask.getFailureMessage()).isNull();
        assertThat(updatedTask.getCompletedAt()).isNotNull();
    }

    @Test
    void markFailureOnlyUpdatesRunningTask() {
        AnalysisTask task = new AnalysisTask(1L, 2L);
        task.markRunning();
        task.markSuccess();
        task = analysisTaskRepository.saveAndFlush(task);

        int updated = analysisTaskRepository.markFailure(
            task.getId(),
            AnalysisTask.Status.FAILED_FINAL,
            AnalysisTask.Status.RUNNING,
            AnalysisFailureCode.UNEXPECTED_ERROR,
            "late failure",
            LocalDateTime.now()
        );

        assertThat(updated).isEqualTo(0);
        AnalysisTask updatedTask = analysisTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(updatedTask.getStatus()).isEqualTo(AnalysisTask.Status.SUCCESS);
        assertThat(updatedTask.getFailureCode()).isNull();
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
