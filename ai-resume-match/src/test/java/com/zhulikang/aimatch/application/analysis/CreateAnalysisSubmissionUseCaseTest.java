package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.application.resume.PrepareResumeUseCase;
import com.zhulikang.aimatch.application.resume.PreparedResume;
import com.zhulikang.aimatch.support.RequestOwnerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(RequestOwnerExtension.class)
class CreateAnalysisSubmissionUseCaseTest {
    @Test
    void preparesResumeBeforeEnteringPersistenceBoundary() {
        PrepareResumeUseCase prepareResumeUseCase = mock(PrepareResumeUseCase.class);
        PersistAnalysisSubmissionUseCase persistUseCase = mock(PersistAnalysisSubmissionUseCase.class);
        PersistIdempotentAnalysisSubmissionUseCase idempotentPersistUseCase =
            mock(PersistIdempotentAnalysisSubmissionUseCase.class);
        CreateAnalysisSubmissionUseCase useCase = new CreateAnalysisSubmissionUseCase(
            prepareResumeUseCase,
            persistUseCase,
            idempotentPersistUseCase
        );
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "resume.pdf",
            "application/pdf",
            new byte[] {1}
        );
        PreparedResume prepared = new PreparedResume("resume.pdf", "Java Redis");
        AnalysisSubmission submission = new AnalysisSubmission(
            new AnalysisTask(1L, 2L),
            "Backend Engineer",
            "resume.pdf"
        );
        when(prepareResumeUseCase.prepare(file)).thenReturn(prepared);
        when(persistUseCase.persist(prepared, "Backend Engineer", "Java Redis"))
            .thenReturn(submission);

        assertThat(useCase.create(file, "Backend Engineer", "Java Redis")).isSameAs(submission);

        InOrder order = inOrder(prepareResumeUseCase, persistUseCase);
        order.verify(prepareResumeUseCase).prepare(file);
        order.verify(persistUseCase).persist(prepared, "Backend Engineer", "Java Redis");
    }

    @Test
    void hashesKeyAndRequestBeforeIdempotentPersistence() {
        PrepareResumeUseCase prepareResumeUseCase = mock(PrepareResumeUseCase.class);
        PersistAnalysisSubmissionUseCase persistUseCase = mock(PersistAnalysisSubmissionUseCase.class);
        PersistIdempotentAnalysisSubmissionUseCase idempotentPersistUseCase =
            mock(PersistIdempotentAnalysisSubmissionUseCase.class);
        CreateAnalysisSubmissionUseCase useCase = new CreateAnalysisSubmissionUseCase(
            prepareResumeUseCase,
            persistUseCase,
            idempotentPersistUseCase
        );
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "resume.pdf",
            "application/pdf",
            new byte[] {1, 2, 3}
        );
        PreparedResume prepared = new PreparedResume("resume.pdf", "Java Redis");
        AnalysisSubmission submission = new AnalysisSubmission(
            new AnalysisTask(1L, 2L),
            "Backend Engineer",
            "resume.pdf"
        );
        when(prepareResumeUseCase.prepare(file)).thenReturn(prepared);
        when(idempotentPersistUseCase.persist(
            org.mockito.ArgumentMatchers.eq(prepared),
            org.mockito.ArgumentMatchers.eq("Backend Engineer"),
            org.mockito.ArgumentMatchers.eq("Java Redis"),
            org.mockito.ArgumentMatchers.any(AnalysisSubmissionIdempotencyContext.class)
        )).thenReturn(submission);

        assertThat(useCase.create(file, "Backend Engineer", "Java Redis", "request-123"))
            .isSameAs(submission);

        ArgumentCaptor<AnalysisSubmissionIdempotencyContext> contextCaptor =
            ArgumentCaptor.forClass(AnalysisSubmissionIdempotencyContext.class);
        verify(idempotentPersistUseCase).persist(
            org.mockito.ArgumentMatchers.eq(prepared),
            org.mockito.ArgumentMatchers.eq("Backend Engineer"),
            org.mockito.ArgumentMatchers.eq("Java Redis"),
            contextCaptor.capture()
        );
        assertThat(contextCaptor.getValue().keyHash()).matches("[0-9a-f]{64}");
        assertThat(contextCaptor.getValue().keyHash()).doesNotContain("request-123");
        assertThat(contextCaptor.getValue().requestFingerprint()).matches("[0-9a-f]{64}");
        verify(persistUseCase, never()).persist(prepared, "Backend Engineer", "Java Redis");
    }

    @Test
    void returnsExistingSubmissionBeforeParsingResumeAgain() {
        PrepareResumeUseCase prepareResumeUseCase = mock(PrepareResumeUseCase.class);
        PersistAnalysisSubmissionUseCase persistUseCase = mock(PersistAnalysisSubmissionUseCase.class);
        PersistIdempotentAnalysisSubmissionUseCase idempotentPersistUseCase =
            mock(PersistIdempotentAnalysisSubmissionUseCase.class);
        CreateAnalysisSubmissionUseCase useCase = new CreateAnalysisSubmissionUseCase(
            prepareResumeUseCase,
            persistUseCase,
            idempotentPersistUseCase
        );
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "resume.pdf",
            "application/pdf",
            new byte[] {1, 2, 3}
        );
        AnalysisSubmission existing = new AnalysisSubmission(
            new AnalysisTask(1L, 2L),
            "Backend Engineer",
            "resume.pdf"
        );
        when(idempotentPersistUseCase.findExisting(
            org.mockito.ArgumentMatchers.any(AnalysisSubmissionIdempotencyContext.class)
        )).thenReturn(java.util.Optional.of(existing));

        assertThat(useCase.create(file, "Backend Engineer", "Java Redis", "request-existing"))
            .isSameAs(existing);

        verifyNoInteractions(prepareResumeUseCase, persistUseCase);
        verify(idempotentPersistUseCase, never()).persist(any(), any(), any(), any());
    }

    @Test
    void resolvesWinnerAfterUniqueConstraintRace() {
        PrepareResumeUseCase prepareResumeUseCase = mock(PrepareResumeUseCase.class);
        PersistAnalysisSubmissionUseCase persistUseCase = mock(PersistAnalysisSubmissionUseCase.class);
        PersistIdempotentAnalysisSubmissionUseCase idempotentPersistUseCase =
            mock(PersistIdempotentAnalysisSubmissionUseCase.class);
        CreateAnalysisSubmissionUseCase useCase = new CreateAnalysisSubmissionUseCase(
            prepareResumeUseCase,
            persistUseCase,
            idempotentPersistUseCase
        );
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[] {1});
        PreparedResume prepared = new PreparedResume("resume.pdf", "Java");
        AnalysisSubmission winner = new AnalysisSubmission(
            new AnalysisTask(1L, 2L),
            "Backend Engineer",
            "resume.pdf"
        );
        when(prepareResumeUseCase.prepare(file)).thenReturn(prepared);
        when(idempotentPersistUseCase.persist(
            org.mockito.ArgumentMatchers.eq(prepared),
            org.mockito.ArgumentMatchers.eq("Backend Engineer"),
            org.mockito.ArgumentMatchers.eq("Java"),
            org.mockito.ArgumentMatchers.any(AnalysisSubmissionIdempotencyContext.class)
        )).thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(idempotentPersistUseCase.findExisting(
            org.mockito.ArgumentMatchers.any(AnalysisSubmissionIdempotencyContext.class)
        )).thenReturn(java.util.Optional.empty(), java.util.Optional.of(winner));

        assertThat(useCase.create(file, "Backend Engineer", "Java", "request-race"))
            .isSameAs(winner);
    }
}
