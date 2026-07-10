package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.application.resume.PrepareResumeUseCase;
import com.zhulikang.aimatch.application.resume.PreparedResume;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreateAnalysisSubmissionUseCaseTest {
    @Test
    void preparesResumeBeforeEnteringPersistenceBoundary() {
        PrepareResumeUseCase prepareResumeUseCase = mock(PrepareResumeUseCase.class);
        PersistAnalysisSubmissionUseCase persistUseCase = mock(PersistAnalysisSubmissionUseCase.class);
        CreateAnalysisSubmissionUseCase useCase = new CreateAnalysisSubmissionUseCase(
            prepareResumeUseCase,
            persistUseCase
        );
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "resume.pdf",
            "application/pdf",
            new byte[] {1}
        );
        PreparedResume prepared = new PreparedResume("resume.pdf", "Java Redis", "Java Redis");
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
}
