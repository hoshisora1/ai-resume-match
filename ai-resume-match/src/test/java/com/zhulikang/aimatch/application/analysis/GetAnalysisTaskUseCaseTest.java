package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.analysis.MatchReport;
import com.zhulikang.aimatch.analysis.MatchReportRepository;
import com.zhulikang.aimatch.api.ResourceNotFoundException;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
        when(resumeRepository.findById(10L)).thenReturn(Optional.of(resume(10L)));
        when(jobRepository.findById(20L)).thenReturn(Optional.of(job(20L)));
        when(reportRepository.findByTaskId(30L)).thenReturn(Optional.of(new MatchReport(30L, 88, "report")));

        Optional<AnalysisTaskDetails> details = useCase.find(30L);

        assertThat(details).get().satisfies(value -> {
            assertThat(value.task()).isSameAs(task);
            assertThat(value.jobTitle()).isEqualTo("高级后端工程师");
            assertThat(value.resumeFileName()).isEqualTo("resume.pdf");
            assertThat(value.matchScore()).isEqualTo(88);
        });
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
        when(resumeRepository.findById(10L)).thenReturn(Optional.of(resume(10L)));
        when(jobRepository.findById(20L)).thenReturn(Optional.of(job(20L)));
        when(reportRepository.findByTaskId(30L)).thenReturn(Optional.empty());

        assertThat(useCase.find(30L)).get().extracting(AnalysisTaskDetails::matchScore).isNull();
    }

    @Test
    void rejectsTaskWhoseResumeIsMissing() {
        AnalysisTask task = task(30L, 10L, 20L);
        when(taskRepository.findById(30L)).thenReturn(Optional.of(task));
        when(resumeRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.find(30L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Resume not found");
    }

    @Test
    void rejectsTaskWhoseJobDescriptionIsMissing() {
        AnalysisTask task = task(30L, 10L, 20L);
        when(taskRepository.findById(30L)).thenReturn(Optional.of(task));
        when(resumeRepository.findById(10L)).thenReturn(Optional.of(resume(10L)));
        when(jobRepository.findById(20L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.find(30L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Job description not found");
    }

    private AnalysisTask task(Long id, Long resumeId, Long jobId) {
        AnalysisTask task = new AnalysisTask(resumeId, jobId);
        ReflectionTestUtils.setField(task, "id", id);
        return task;
    }

    private Resume resume(Long id) {
        Resume resume = new Resume("resume.pdf", "raw", "summary");
        ReflectionTestUtils.setField(resume, "id", id);
        return resume;
    }

    private JobDescription job(Long id) {
        JobDescription job = new JobDescription("高级后端工程师", "content", "Java");
        ReflectionTestUtils.setField(job, "id", id);
        return job;
    }
}
