package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.api.ResourceNotFoundException;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.ResumeRepository;
import com.zhulikang.aimatch.support.RequestOwnerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.zhulikang.aimatch.support.RequestOwnerExtension.OWNER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(RequestOwnerExtension.class)
class CreateAnalysisTaskUseCaseTest {
    @Test
    void rejectsMissingResume() {
        ResumeRepository resumeRepository = mock(ResumeRepository.class);
        JobDescriptionRepository jobRepository = mock(JobDescriptionRepository.class);
        AnalysisTaskCreator taskCreator = mock(AnalysisTaskCreator.class);
        CreateAnalysisTaskUseCase useCase = new CreateAnalysisTaskUseCase(
            resumeRepository,
            jobRepository,
            taskCreator
        );
        when(resumeRepository.existsByIdAndOwnerId(1L, OWNER_ID)).thenReturn(false);

        assertThatThrownBy(() -> useCase.create(1L, 2L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Resume not found");
        verifyNoInteractions(jobRepository, taskCreator);
    }

    @Test
    void rejectsMissingJobDescription() {
        ResumeRepository resumeRepository = mock(ResumeRepository.class);
        JobDescriptionRepository jobRepository = mock(JobDescriptionRepository.class);
        AnalysisTaskCreator taskCreator = mock(AnalysisTaskCreator.class);
        CreateAnalysisTaskUseCase useCase = new CreateAnalysisTaskUseCase(
            resumeRepository,
            jobRepository,
            taskCreator
        );
        when(resumeRepository.existsByIdAndOwnerId(1L, OWNER_ID)).thenReturn(true);
        when(jobRepository.existsByIdAndOwnerId(2L, OWNER_ID)).thenReturn(false);

        assertThatThrownBy(() -> useCase.create(1L, 2L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Job description not found");
        verifyNoInteractions(taskCreator);
    }

    @Test
    void delegatesTaskCreationAfterExistenceChecks() {
        ResumeRepository resumeRepository = mock(ResumeRepository.class);
        JobDescriptionRepository jobRepository = mock(JobDescriptionRepository.class);
        AnalysisTaskCreator taskCreator = mock(AnalysisTaskCreator.class);
        CreateAnalysisTaskUseCase useCase = new CreateAnalysisTaskUseCase(
            resumeRepository,
            jobRepository,
            taskCreator
        );
        AnalysisTask saved = new AnalysisTask(1L, 2L);
        when(resumeRepository.existsByIdAndOwnerId(1L, OWNER_ID)).thenReturn(true);
        when(jobRepository.existsByIdAndOwnerId(2L, OWNER_ID)).thenReturn(true);
        when(taskCreator.create(1L, 2L)).thenReturn(saved);

        assertThat(useCase.create(1L, 2L)).isSameAs(saved);

        verify(resumeRepository).existsByIdAndOwnerId(1L, OWNER_ID);
        verify(jobRepository).existsByIdAndOwnerId(2L, OWNER_ID);
        verify(taskCreator).create(1L, 2L);
    }
}
