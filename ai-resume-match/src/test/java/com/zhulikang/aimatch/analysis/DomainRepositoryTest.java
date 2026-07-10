package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionDisplayView;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeDisplayView;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

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
    @Autowired
    AnalysisOutboxRepository outboxRepository;

    @Test
    void persistsResumeJobTaskAndReport() {
        Resume resume = resumeRepository.save(new Resume(
            "resume.docx",
            "Java Spring Boot Redis",
            "skills: Java, Redis"
        ));
        JobDescription job = jobDescriptionRepository.save(new JobDescription(
            "高级后端工程师",
            "熟悉 Java Redis Kafka",
            "Java,Redis,Kafka"
        ));
        AnalysisTask task = analysisTaskRepository.save(new AnalysisTask(resume.getId(), job.getId()));
        MatchReport report = matchReportRepository.save(new MatchReport(task.getId(), 88, "匹配分数：88"));

        assertThat(task.getStatus()).isEqualTo(AnalysisTask.Status.PENDING);
        assertThat(job.getTitle()).isEqualTo("高级后端工程师");
        assertThat(report.getTaskId()).isEqualTo(task.getId());
        assertThat(matchReportRepository.findByTaskId(task.getId()))
            .contains(report);
    }

    @Test
    void readsResumeDisplayProjections() {
        Resume resume = resumeRepository.save(new Resume("resume.pdf", "fixture", "fixture"));

        assertThat(resumeRepository.findDisplayViewsByIdIn(List.of(resume.getId())))
            .containsExactly(new ResumeDisplayView(resume.getId(), "resume.pdf"));
        assertThat(resumeRepository.findDisplayViewById(resume.getId()))
            .contains(new ResumeDisplayView(resume.getId(), "resume.pdf"));
    }

    @Test
    void readsJobDescriptionDisplayProjections() {
        JobDescription job = jobDescriptionRepository.save(new JobDescription(
            "高级后端工程师",
            "fixture",
            "fixture"
        ));

        assertThat(jobDescriptionRepository.findDisplayViewsByIdIn(List.of(job.getId())))
            .containsExactly(new JobDescriptionDisplayView(job.getId(), "高级后端工程师"));
        assertThat(jobDescriptionRepository.findDisplayViewById(job.getId()))
            .contains(new JobDescriptionDisplayView(job.getId(), "高级后端工程师"));
    }

    @Test
    void findsTasksByStatusInNewestStableOrder() {
        LocalDateTime older = LocalDateTime.of(2026, 7, 10, 8, 0);
        LocalDateTime newer = LocalDateTime.of(2026, 7, 10, 9, 0);
        AnalysisTask olderSuccess = taskWithStatusAndCreatedAt(AnalysisTask.Status.SUCCESS, older);
        AnalysisTask firstNewerSuccess = taskWithStatusAndCreatedAt(AnalysisTask.Status.SUCCESS, newer);
        AnalysisTask secondNewerSuccess = taskWithStatusAndCreatedAt(AnalysisTask.Status.SUCCESS, newer);
        taskWithStatusAndCreatedAt(AnalysisTask.Status.PENDING, newer.plusHours(1));

        assertThat(analysisTaskRepository.findByStatus(
            AnalysisTask.Status.SUCCESS,
            PageRequest.of(0, 20, Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")
            ))
        )).extracting(AnalysisTask::getId).containsExactly(
            secondNewerSuccess.getId(),
            firstNewerSuccess.getId(),
            olderSuccess.getId()
        );
    }

    @Test
    void countsTasksByOneOrSeveralStatuses() {
        taskWithStatusAndCreatedAt(AnalysisTask.Status.PENDING, LocalDateTime.now());
        taskWithStatusAndCreatedAt(AnalysisTask.Status.RUNNING, LocalDateTime.now());
        taskWithStatusAndCreatedAt(AnalysisTask.Status.SUCCESS, LocalDateTime.now());

        assertThat(analysisTaskRepository.countByStatus(AnalysisTask.Status.SUCCESS)).isEqualTo(1);
        assertThat(analysisTaskRepository.countByStatusIn(List.of(
            AnalysisTask.Status.PENDING,
            AnalysisTask.Status.RUNNING
        ))).isEqualTo(2);
    }

    @Test
    void readsMatchScoreProjectionsAndCalculatesAverageScore() {
        matchReportRepository.save(new MatchReport(101L, 82, "fixture"));
        matchReportRepository.save(new MatchReport(102L, 83, "fixture"));
        matchReportRepository.save(new MatchReport(103L, 95, "fixture"));

        assertThat(matchReportRepository.findScoreViewsByTaskIdIn(List.of(101L, 102L)))
            .containsExactlyInAnyOrder(new MatchScoreView(101L, 82), new MatchScoreView(102L, 83));
        assertThat(matchReportRepository.findScoreViewByTaskId(101L))
            .contains(new MatchScoreView(101L, 82));
        assertThat(matchReportRepository.averageMatchScore()).isEqualTo(86.66666666666667);
    }

    @Test
    void persistsAnalysisOutboxEvent() {
        AnalysisOutboxEvent event = outboxRepository.save(AnalysisOutboxEvent.analysisRequested(99L));

        assertThat(outboxRepository.findAll())
            .singleElement()
            .satisfies(saved -> {
                assertThat(saved.getId()).isEqualTo(event.getId());
                assertThat(saved.getEventType()).isEqualTo(AnalysisOutboxEventType.ANALYSIS_REQUESTED);
                assertThat(saved.getAggregateType()).isEqualTo("analysis_task");
                assertThat(saved.getAggregateId()).isEqualTo(99L);
                assertThat(saved.getStatus()).isEqualTo(AnalysisOutboxStatus.PENDING);
                assertThat(saved.getPayloadJson()).contains("\"taskId\":99");
            });
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
            LocalDateTime.now()
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
            null,
            LocalDateTime.now()
        );

        assertThat(updated).isEqualTo(0);
        AnalysisTask updatedTask = analysisTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(updatedTask.getStatus()).isEqualTo(AnalysisTask.Status.SUCCESS);
        assertThat(updatedTask.getFailureCode()).isNull();
    }

    @Test
    void doesNotStartFreshRunningTaskEvenWhenMessageIsRedelivered() {
        AnalysisTask task = analysisTaskRepository.save(new AnalysisTask(1L, 2L));
        task.markRunning();
        analysisTaskRepository.saveAndFlush(task);

        AnalysisTaskService service = new AnalysisTaskService(
            analysisTaskRepository,
            matchReportRepository,
            Duration.ofMinutes(15)
        );

        assertThat(service.tryStart(task.getId(), true)).isFalse();
        AnalysisTask updatedTask = analysisTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(updatedTask.getStatus()).isEqualTo(AnalysisTask.Status.RUNNING);
        assertThat(updatedTask.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void doesNotReclaimFreshRunningTaskWhenMessageIsNotRedelivered() {
        AnalysisTask task = analysisTaskRepository.save(new AnalysisTask(1L, 2L));
        task.markRunning();
        analysisTaskRepository.saveAndFlush(task);

        int updated = analysisTaskRepository.markRunningIfPendingOrStale(
            task.getId(),
            AnalysisTask.Status.RUNNING,
            AnalysisTask.Status.PENDING,
            LocalDateTime.now().minusMinutes(15),
            LocalDateTime.now()
        );

        assertThat(updated).isEqualTo(0);
        AnalysisTask updatedTask = analysisTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(updatedTask.getStatus()).isEqualTo(AnalysisTask.Status.RUNNING);
        assertThat(updatedTask.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void canFindAndResetDueRetryableTasks() {
        LocalDateTime now = LocalDateTime.now();
        AnalysisTask task = new AnalysisTask(1L, 2L);
        task.markRunning();
        task.markRetryableFailure(AnalysisFailureCode.AI_UNAVAILABLE, "AI unavailable", now.minusMinutes(1));
        task = analysisTaskRepository.saveAndFlush(task);

        assertThat(analysisTaskRepository.findDueRetryableTasks(
            AnalysisTask.Status.FAILED_RETRYABLE,
            now,
            PageRequest.of(0, 20)
        )).extracting(AnalysisTask::getId).contains(task.getId());

        int updated = analysisTaskRepository.markRetryableAsPending(
            task.getId(),
            AnalysisTask.Status.PENDING,
            AnalysisTask.Status.FAILED_RETRYABLE,
            now
        );

        assertThat(updated).isEqualTo(1);
        AnalysisTask updatedTask = analysisTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(updatedTask.getStatus()).isEqualTo(AnalysisTask.Status.PENDING);
        assertThat(updatedTask.getFailureCode()).isNull();
        assertThat(updatedTask.getFailureMessage()).isNull();
        assertThat(updatedTask.getNextRetryAt()).isNull();
        assertThat(updatedTask.getCompletedAt()).isNull();
    }

    private AnalysisTask taskWithStatusAndCreatedAt(AnalysisTask.Status status, LocalDateTime createdAt) {
        AnalysisTask task = new AnalysisTask(1L, 2L);
        ReflectionTestUtils.setField(task, "status", status);
        ReflectionTestUtils.setField(task, "createdAt", createdAt);
        ReflectionTestUtils.setField(task, "updatedAt", createdAt);
        return analysisTaskRepository.saveAndFlush(task);
    }
}
