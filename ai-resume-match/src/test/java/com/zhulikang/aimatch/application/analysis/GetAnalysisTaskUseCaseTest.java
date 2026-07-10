package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.analysis.MatchReportRepository;
import com.zhulikang.aimatch.analysis.MatchScoreView;
import com.zhulikang.aimatch.api.ResourceNotFoundException;
import com.zhulikang.aimatch.job.JobDescriptionDisplayView;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.ResumeDisplayView;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GetAnalysisTaskUseCaseTest {
    private AnalysisTaskRepository taskRepository;
    private ResumeRepository resumeRepository;
    private JobDescriptionRepository jobRepository;
    private MatchReportRepository reportRepository;
    private GetAnalysisTaskUseCase useCase;

    @BeforeEach
    void setUp() {
        taskRepository = mock(AnalysisTaskRepository.class);
        resumeRepository = mock(ResumeRepository.class);
        jobRepository = mock(JobDescriptionRepository.class);
        reportRepository = mock(MatchReportRepository.class);
        useCase = new GetAnalysisTaskUseCase(
            taskRepository,
            resumeRepository,
            jobRepository,
            reportRepository
        );
    }

    @Test
    void returnsEnrichedTaskDetails() {
        AnalysisTask task = task(30L, 10L, 20L);
        when(taskRepository.findById(30L)).thenReturn(Optional.of(task));
        when(resumeRepository.findDisplayViewById(10L)).thenReturn(Optional.of(resume(10L)));
        when(jobRepository.findDisplayViewById(20L)).thenReturn(Optional.of(job(20L)));
        when(reportRepository.findScoreViewByTaskId(30L)).thenReturn(Optional.of(new MatchScoreView(30L, 88)));

        Optional<AnalysisTaskDetails> details = useCase.find(30L);

        assertThat(details).get().satisfies(value -> {
            assertThat(value.task()).isSameAs(task);
            assertThat(value.jobTitle()).isEqualTo("高级后端工程师");
            assertThat(value.resumeFileName()).isEqualTo("resume.pdf");
            assertThat(value.matchScore()).isEqualTo(88);
        });
        verify(resumeRepository).findDisplayViewById(10L);
        verify(jobRepository).findDisplayViewById(20L);
        verify(reportRepository).findScoreViewByTaskId(30L);
        verify(resumeRepository, never()).findById(10L);
        verify(jobRepository, never()).findById(20L);
        verify(reportRepository, never()).findByTaskId(30L);
    }

    @Test
    void returnsEmptyWhenTaskDoesNotExist() {
        when(taskRepository.findById(404L)).thenReturn(Optional.empty());

        assertThat(useCase.find(404L)).isEmpty();
        verifyNoInteractions(resumeRepository, jobRepository, reportRepository);
    }

    @Test
    void allowsTaskWithoutMatchReport() {
        AnalysisTask task = task(30L, 10L, 20L);
        when(taskRepository.findById(30L)).thenReturn(Optional.of(task));
        when(resumeRepository.findDisplayViewById(10L)).thenReturn(Optional.of(resume(10L)));
        when(jobRepository.findDisplayViewById(20L)).thenReturn(Optional.of(job(20L)));
        when(reportRepository.findScoreViewByTaskId(30L)).thenReturn(Optional.empty());

        assertThat(useCase.find(30L)).get().extracting(AnalysisTaskDetails::matchScore).isNull();
    }

    @Test
    void rejectsTaskWhoseResumeIsMissing() {
        AnalysisTask task = task(30L, 10L, 20L);
        when(taskRepository.findById(30L)).thenReturn(Optional.of(task));
        when(resumeRepository.findDisplayViewById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.find(30L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Resume not found");
    }

    @Test
    void rejectsTaskWhoseJobDescriptionIsMissing() {
        AnalysisTask task = task(30L, 10L, 20L);
        when(taskRepository.findById(30L)).thenReturn(Optional.of(task));
        when(resumeRepository.findDisplayViewById(10L)).thenReturn(Optional.of(resume(10L)));
        when(jobRepository.findDisplayViewById(20L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.find(30L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Job description not found");
    }

    private AnalysisTask task(Long id, Long resumeId, Long jobId) {
        AnalysisTask task = new AnalysisTask(resumeId, jobId);
        ReflectionTestUtils.setField(task, "id", id);
        return task;
    }

    private ResumeDisplayView resume(Long id) {
        return new ResumeDisplayView(id, "resume.pdf");
    }

    private JobDescriptionDisplayView job(Long id) {
        return new JobDescriptionDisplayView(id, "高级后端工程师");
    }
}
