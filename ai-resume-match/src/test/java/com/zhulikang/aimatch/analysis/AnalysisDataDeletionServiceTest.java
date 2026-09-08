package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DataJpaTest
@Import(AnalysisDataDeletionService.class)
class AnalysisDataDeletionServiceTest {
    private static final String OWNER = "a".repeat(64);
    private static final String OTHER_OWNER = "b".repeat(64);

    @Autowired
    AnalysisDataDeletionService deletionService;
    @Autowired
    AnalysisTaskRepository taskRepository;
    @Autowired
    MatchReportRepository reportRepository;
    @Autowired
    AnalysisSubmissionIdempotencyRepository idempotencyRepository;
    @Autowired
    AnalysisOutboxRepository outboxRepository;
    @Autowired
    ResumeRepository resumeRepository;
    @Autowired
    JobDescriptionRepository jobRepository;
    @MockBean
    ReportCache reportCache;

    @Test
    void deletesOwnedTaskAndEveryUnsharedPersistedArtifact() {
        Resources resources = resources();
        AnalysisTask task = taskRepository.saveAndFlush(
            new AnalysisTask(OWNER, resources.resume().getId(), resources.job().getId())
        );
        task.markRunning();
        task.markSuccess();
        task = taskRepository.saveAndFlush(task);
        MatchReport report = reportRepository.saveAndFlush(
            new MatchReport(task.getId(), 88, "grounded report")
        );
        AnalysisSubmissionIdempotencyRecord idempotency = new AnalysisSubmissionIdempotencyRecord(
            OWNER,
            "c".repeat(64),
            "d".repeat(64)
        );
        idempotency.complete(task.getId());
        idempotency = idempotencyRepository.saveAndFlush(idempotency);
        AnalysisOutboxEvent outbox = outboxRepository.saveAndFlush(
            AnalysisOutboxEvent.analysisRequested(task.getId())
        );

        assertThat(deletionService.deleteOwned(task.getId(), OWNER)).isTrue();

        assertThat(taskRepository.findById(task.getId())).isEmpty();
        assertThat(reportRepository.findById(report.getId())).isEmpty();
        assertThat(idempotencyRepository.findById(idempotency.getId())).isEmpty();
        assertThat(outboxRepository.findById(outbox.getId())).isEmpty();
        assertThat(resumeRepository.findById(resources.resume().getId())).isEmpty();
        assertThat(jobRepository.findById(resources.job().getId())).isEmpty();
        verify(reportCache).evict(task.getId());
    }

    @Test
    void keepsSharedSourcesUntilTheirLastTaskIsDeleted() {
        Resources resources = resources();
        AnalysisTask first = taskRepository.saveAndFlush(
            new AnalysisTask(OWNER, resources.resume().getId(), resources.job().getId())
        );
        AnalysisTask second = taskRepository.saveAndFlush(
            new AnalysisTask(OWNER, resources.resume().getId(), resources.job().getId())
        );

        assertThat(deletionService.deleteOwned(first.getId(), OWNER)).isTrue();

        assertThat(taskRepository.findById(second.getId())).isPresent();
        assertThat(resumeRepository.findById(resources.resume().getId())).isPresent();
        assertThat(jobRepository.findById(resources.job().getId())).isPresent();
    }

    @Test
    void crossOwnerDeletionLooksMissingAndDoesNotEvictCache() {
        Resources resources = resources();
        AnalysisTask task = taskRepository.saveAndFlush(
            new AnalysisTask(OWNER, resources.resume().getId(), resources.job().getId())
        );

        assertThat(deletionService.deleteOwned(task.getId(), OTHER_OWNER)).isFalse();

        assertThat(taskRepository.findById(task.getId())).isPresent();
        verify(reportCache, never()).evict(task.getId());
    }

    @Test
    void deletedRunningTaskRejectsLateWorkerCompletion() {
        Resources resources = resources();
        AnalysisTask task = new AnalysisTask(OWNER, resources.resume().getId(), resources.job().getId());
        task.markRunning();
        task = taskRepository.saveAndFlush(task);
        Long taskId = task.getId();

        assertThat(deletionService.deleteOwned(taskId, OWNER)).isTrue();

        AnalysisTaskService taskService = new AnalysisTaskService(
            taskRepository,
            reportRepository,
            Duration.ofMinutes(15)
        );
        assertThat(taskService.completeSuccess(
            new MatchReport(taskId, 91, "late result"),
            1
        )).isFalse();
        assertThat(reportRepository.findByTaskId(taskId)).isEmpty();
    }

    @Test
    void retentionDeletesOldActiveTaskButRevalidatesAgeUnderTheRowLock() {
        Resources oldResources = resources();
        AnalysisTask oldPending = new AnalysisTask(
            OWNER,
            oldResources.resume().getId(),
            oldResources.job().getId()
        );
        ReflectionTestUtils.setField(oldPending, "createdAt", LocalDateTime.now().minusDays(31));
        oldPending = taskRepository.saveAndFlush(oldPending);
        Resources recentResources = resources();
        AnalysisTask recentPending = taskRepository.saveAndFlush(new AnalysisTask(
            OWNER,
            recentResources.resume().getId(),
            recentResources.job().getId()
        ));
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);

        assertThat(deletionService.deleteExpired(oldPending.getId(), cutoff)).isTrue();
        assertThat(deletionService.deleteExpired(recentPending.getId(), cutoff)).isFalse();

        assertThat(taskRepository.findById(oldPending.getId())).isEmpty();
        assertThat(taskRepository.findById(recentPending.getId())).isPresent();
    }

    private Resources resources() {
        Resume resume = resumeRepository.saveAndFlush(
            new Resume(OWNER, "synthetic-resume.pdf", "Java Redis")
        );
        JobDescription job = jobRepository.saveAndFlush(
            new JobDescription(OWNER, "Backend Engineer", "Java Redis", "Java,Redis")
        );
        return new Resources(resume, job);
    }

    private record Resources(Resume resume, JobDescription job) {
    }
}
