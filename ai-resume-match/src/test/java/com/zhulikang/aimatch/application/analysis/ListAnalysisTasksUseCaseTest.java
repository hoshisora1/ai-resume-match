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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ListAnalysisTasksUseCaseTest {
    private AnalysisTaskRepository taskRepository;
    private ResumeRepository resumeRepository;
    private JobDescriptionRepository jobRepository;
    private MatchReportRepository reportRepository;
    private ListAnalysisTasksUseCase useCase;

    @BeforeEach
    void setUp() {
        taskRepository = mock(AnalysisTaskRepository.class);
        resumeRepository = mock(ResumeRepository.class);
        jobRepository = mock(JobDescriptionRepository.class);
        reportRepository = mock(MatchReportRepository.class);
        useCase = new ListAnalysisTasksUseCase(
            taskRepository,
            resumeRepository,
            jobRepository,
            reportRepository
        );
    }

    @Test
    void listsNewestTasksWithDisplayMetadataAndScore() {
        AnalysisTask task = successfulTask(30L, 10L, 20L);
        Resume resume = resume(10L, "resume.pdf");
        JobDescription job = job(20L, "高级后端工程师");
        MatchReport report = new MatchReport(30L, 88, "report");
        when(taskRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(
            List.of(task),
            PageRequest.of(0, 20),
            41
        ));
        when(resumeRepository.findAllById(any())).thenReturn(List.of(resume));
        when(jobRepository.findAllById(any())).thenReturn(List.of(job));
        when(reportRepository.findAllByTaskIdIn(anyCollection())).thenReturn(List.of(report));

        AnalysisPage page = useCase.list(null, 0, 20);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.taskId()).isEqualTo(30L);
            assertThat(item.jobTitle()).isEqualTo("高级后端工程师");
            assertThat(item.resumeFileName()).isEqualTo("resume.pdf");
            assertThat(item.status()).isEqualTo(AnalysisTask.Status.SUCCESS);
            assertThat(item.matchScore()).isEqualTo(88);
            assertThat(item.attemptCount()).isEqualTo(1);
            assertThat(item.maxAttempts()).isEqualTo(3);
            assertThat(item.createdAt()).isEqualTo(LocalDateTime.of(2026, 7, 10, 9, 0));
        });
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(41);
        assertThat(page.totalPages()).isEqualTo(3);

        var pageableCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(taskRepository).findAll(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort()).containsExactly(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id")
        );
        verify(resumeRepository).findAllById(any());
        verify(jobRepository).findAllById(any());
        verify(reportRepository).findAllByTaskIdIn(anyCollection());
        verify(resumeRepository, never()).findById(any());
        verify(jobRepository, never()).findById(any());
        verify(reportRepository, never()).findByTaskId(any());
    }

    @Test
    void filtersTasksByStatus() {
        when(taskRepository.findByStatus(eq(AnalysisTask.Status.SUCCESS), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        AnalysisPage page = useCase.list(AnalysisTask.Status.SUCCESS, 0, 20);

        assertThat(page.items()).isEmpty();
        verify(taskRepository).findByStatus(eq(AnalysisTask.Status.SUCCESS), any(Pageable.class));
        verify(taskRepository, never()).findAll(any(Pageable.class));
        verifyNoInteractions(resumeRepository, jobRepository, reportRepository);
    }

    @Test
    void allowsTaskWithoutMatchReport() {
        AnalysisTask task = new AnalysisTask(10L, 20L);
        ReflectionTestUtils.setField(task, "id", 30L);
        stubSingleTaskPage(task);
        when(resumeRepository.findAllById(any())).thenReturn(List.of(resume(10L, "resume.pdf")));
        when(jobRepository.findAllById(any())).thenReturn(List.of(job(20L, "Backend Engineer")));
        when(reportRepository.findAllByTaskIdIn(anyCollection())).thenReturn(List.of());

        AnalysisPage page = useCase.list(null, 0, 20);

        assertThat(page.items()).singleElement().extracting(AnalysisListItem::matchScore).isNull();
    }

    @Test
    void rejectsTaskWhoseResumeIsMissing() {
        AnalysisTask task = new AnalysisTask(10L, 20L);
        ReflectionTestUtils.setField(task, "id", 30L);
        stubSingleTaskPage(task);
        when(resumeRepository.findAllById(any())).thenReturn(List.of());
        when(jobRepository.findAllById(any())).thenReturn(List.of(job(20L, "Backend Engineer")));
        when(reportRepository.findAllByTaskIdIn(anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> useCase.list(null, 0, 20))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Resume not found");
    }

    @Test
    void rejectsTaskWhoseJobDescriptionIsMissing() {
        AnalysisTask task = new AnalysisTask(10L, 20L);
        ReflectionTestUtils.setField(task, "id", 30L);
        stubSingleTaskPage(task);
        when(resumeRepository.findAllById(any())).thenReturn(List.of(resume(10L, "resume.pdf")));
        when(jobRepository.findAllById(any())).thenReturn(List.of());
        when(reportRepository.findAllByTaskIdIn(anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> useCase.list(null, 0, 20))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Job description not found");
    }

    @ParameterizedTest
    @CsvSource({"-1,20,Page must not be negative", "0,0,Size must be between 1 and 100", "0,101,Size must be between 1 and 100"})
    void rejectsInvalidPagination(int page, int size, String message) {
        assertThatThrownBy(() -> useCase.list(null, page, size))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(message);
        verifyNoInteractions(taskRepository, resumeRepository, jobRepository, reportRepository);
    }

    private void stubSingleTaskPage(AnalysisTask task) {
        when(taskRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(
            List.of(task),
            PageRequest.of(0, 20),
            1
        ));
    }

    private AnalysisTask successfulTask(Long id, Long resumeId, Long jobId) {
        AnalysisTask task = new AnalysisTask(resumeId, jobId);
        ReflectionTestUtils.setField(task, "id", id);
        task.markRunning();
        task.markSuccess();
        ReflectionTestUtils.setField(task, "createdAt", LocalDateTime.of(2026, 7, 10, 9, 0));
        ReflectionTestUtils.setField(task, "updatedAt", LocalDateTime.of(2026, 7, 10, 9, 5));
        ReflectionTestUtils.setField(task, "completedAt", LocalDateTime.of(2026, 7, 10, 9, 5));
        return task;
    }

    private Resume resume(Long id, String fileName) {
        Resume resume = new Resume(fileName, "raw", "summary");
        ReflectionTestUtils.setField(resume, "id", id);
        return resume;
    }

    private JobDescription job(Long id, String title) {
        JobDescription job = new JobDescription(title, "content", "Java");
        ReflectionTestUtils.setField(job, "id", id);
        return job;
    }
}
