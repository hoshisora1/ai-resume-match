package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.api.ResourceNotFoundException;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import com.zhulikang.aimatch.resume.ResumeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreateAnalysisTaskUseCaseTest {
    @Test
    void rejectsMissingResume() {
        ResumeRepository resumeRepository = mock(ResumeRepository.class);
        JobDescriptionRepository jobRepository = mock(JobDescriptionRepository.class);
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
        CreateAnalysisTaskUseCase useCase = new CreateAnalysisTaskUseCase(
            resumeRepository,
            jobRepository,
            taskRepository,
            publisher,
            new AnalysisMetrics(new SimpleMeterRegistry())
        );
        when(resumeRepository.existsById(1L)).thenReturn(false);

        assertThatThrownBy(() -> useCase.create(1L, 2L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Resume not found");
        verifyNoInteractions(taskRepository, publisher);
    }

    @Test
    void rejectsMissingJobDescription() {
        ResumeRepository resumeRepository = mock(ResumeRepository.class);
        JobDescriptionRepository jobRepository = mock(JobDescriptionRepository.class);
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
        CreateAnalysisTaskUseCase useCase = new CreateAnalysisTaskUseCase(
            resumeRepository,
            jobRepository,
            taskRepository,
            publisher,
            new AnalysisMetrics(new SimpleMeterRegistry())
        );
        when(resumeRepository.existsById(1L)).thenReturn(true);
        when(jobRepository.existsById(2L)).thenReturn(false);

        assertThatThrownBy(() -> useCase.create(1L, 2L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Job description not found");
        verifyNoInteractions(taskRepository, publisher);
    }

    @Test
    void savesAnalysisTaskAndPublishesTaskIdAfterCommit() {
        ResumeRepository resumeRepository = mock(ResumeRepository.class);
        JobDescriptionRepository jobRepository = mock(JobDescriptionRepository.class);
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CreateAnalysisTaskUseCase useCase = new CreateAnalysisTaskUseCase(
            resumeRepository,
            jobRepository,
            taskRepository,
            publisher,
            new AnalysisMetrics(meterRegistry)
        );
        AnalysisTask saved = new AnalysisTask(1L, 2L);
        ReflectionTestUtils.setField(saved, "id", 99L);
        when(resumeRepository.existsById(1L)).thenReturn(true);
        when(jobRepository.existsById(2L)).thenReturn(true);
        when(taskRepository.save(any(AnalysisTask.class))).thenReturn(saved);

        AnalysisTask task = useCase.create(1L, 2L);

        assertThat(task.getResumeId()).isEqualTo(1L);
        assertThat(task.getJobDescriptionId()).isEqualTo(2L);
        verify(taskRepository).save(any(AnalysisTask.class));
        verify(publisher).publishAfterCommit(99L);
        assertThat(meterRegistry.counter("analysis.tasks.created").count()).isEqualTo(1.0);
    }
}
