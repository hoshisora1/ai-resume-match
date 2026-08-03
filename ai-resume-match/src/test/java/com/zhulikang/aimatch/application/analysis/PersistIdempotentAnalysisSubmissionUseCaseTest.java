package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisSubmissionIdempotencyRecord;
import com.zhulikang.aimatch.analysis.AnalysisSubmissionIdempotencyRepository;
import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.application.resume.PreparedResume;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PersistIdempotentAnalysisSubmissionUseCaseTest {
    private static final String KEY_HASH = "a".repeat(64);
    private static final String FINGERPRINT = "b".repeat(64);

    private AnalysisSubmissionIdempotencyRepository idempotencyRepository;
    private PersistAnalysisSubmissionUseCase delegate;
    private AnalysisTaskRepository taskRepository;
    private ResumeRepository resumeRepository;
    private JobDescriptionRepository jobRepository;
    private PersistIdempotentAnalysisSubmissionUseCase useCase;

    @BeforeEach
    void setUp() {
        idempotencyRepository = mock(AnalysisSubmissionIdempotencyRepository.class);
        delegate = mock(PersistAnalysisSubmissionUseCase.class);
        taskRepository = mock(AnalysisTaskRepository.class);
        resumeRepository = mock(ResumeRepository.class);
        jobRepository = mock(JobDescriptionRepository.class);
        useCase = new PersistIdempotentAnalysisSubmissionUseCase(
            idempotencyRepository,
            delegate,
            taskRepository,
            resumeRepository,
            jobRepository
        );
    }

    @Test
    void reservesUniqueKeyBeforeCreatingSubmission() {
        PreparedResume prepared = new PreparedResume("resume.pdf", "Java", "Java");
        AnalysisSubmissionIdempotencyRecord record = new AnalysisSubmissionIdempotencyRecord(
            KEY_HASH, FINGERPRINT
        );
        AnalysisTask task = task(30L);
        AnalysisSubmission submission = new AnalysisSubmission(task, "Backend Engineer", "resume.pdf");
        when(idempotencyRepository.findByIdempotencyKeyHash(KEY_HASH)).thenReturn(Optional.empty());
        when(idempotencyRepository.saveAndFlush(any(AnalysisSubmissionIdempotencyRecord.class)))
            .thenReturn(record);
        when(delegate.persist(prepared, "Backend Engineer", "Java")).thenReturn(submission);

        assertThat(useCase.persist(prepared, "Backend Engineer", "Java", context(FINGERPRINT)))
            .isSameAs(submission);

        InOrder order = inOrder(idempotencyRepository, delegate);
        order.verify(idempotencyRepository).saveAndFlush(any(AnalysisSubmissionIdempotencyRecord.class));
        order.verify(delegate).persist(prepared, "Backend Engineer", "Java");
        order.verify(idempotencyRepository).flush();
        assertThat(record.getTaskId()).isEqualTo(30L);
    }

    @Test
    void returnsOriginalSubmissionForSameFingerprint() {
        AnalysisSubmissionIdempotencyRecord record = completedRecord(FINGERPRINT, 30L);
        AnalysisTask task = task(30L);
        Resume resume = new Resume("resume.pdf", "Java", "Java");
        JobDescription job = new JobDescription("Backend Engineer", "Java", "Java");
        when(idempotencyRepository.findByIdempotencyKeyHash(KEY_HASH)).thenReturn(Optional.of(record));
        when(taskRepository.findById(30L)).thenReturn(Optional.of(task));
        when(resumeRepository.findById(10L)).thenReturn(Optional.of(resume));
        when(jobRepository.findById(20L)).thenReturn(Optional.of(job));

        AnalysisSubmission result = useCase.persist(
            new PreparedResume("ignored.pdf", "ignored", "ignored"),
            "Ignored",
            "Ignored",
            context(FINGERPRINT)
        );

        assertThat(result.task()).isSameAs(task);
        assertThat(result.resumeFileName()).isEqualTo("resume.pdf");
        assertThat(result.jobTitle()).isEqualTo("Backend Engineer");
        verifyNoInteractions(delegate);
    }

    @Test
    void rejectsSameKeyWithDifferentFingerprint() {
        when(idempotencyRepository.findByIdempotencyKeyHash(KEY_HASH))
            .thenReturn(Optional.of(completedRecord(FINGERPRINT, 30L)));

        assertThatThrownBy(() -> useCase.persist(
            new PreparedResume("resume.pdf", "Java", "Java"),
            "Backend Engineer",
            "Java",
            context("c".repeat(64))
        )).isInstanceOf(IdempotencyConflictException.class)
            .hasMessage(IdempotencyConflictException.MESSAGE);
        verifyNoInteractions(delegate);
    }

    @Test
    void uniqueReservationFailureOccursBeforeAnyBusinessPersistence() {
        when(idempotencyRepository.findByIdempotencyKeyHash(KEY_HASH)).thenReturn(Optional.empty());
        when(idempotencyRepository.saveAndFlush(any(AnalysisSubmissionIdempotencyRecord.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> useCase.persist(
            new PreparedResume("resume.pdf", "Java", "Java"),
            "Backend Engineer",
            "Java",
            context(FINGERPRINT)
        )).isInstanceOf(DataIntegrityViolationException.class);

        verify(delegate, never()).persist(any(), any(), any());
        verifyNoInteractions(taskRepository, resumeRepository, jobRepository);
    }

    private AnalysisSubmissionIdempotencyContext context(String fingerprint) {
        return new AnalysisSubmissionIdempotencyContext(KEY_HASH, fingerprint);
    }

    private AnalysisSubmissionIdempotencyRecord completedRecord(String fingerprint, Long taskId) {
        AnalysisSubmissionIdempotencyRecord record = new AnalysisSubmissionIdempotencyRecord(
            KEY_HASH, fingerprint
        );
        record.complete(taskId);
        return record;
    }

    private AnalysisTask task(Long id) {
        AnalysisTask task = new AnalysisTask(10L, 20L);
        ReflectionTestUtils.setField(task, "id", id);
        return task;
    }
}
