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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ListAnalysisTasksUseCase {
    private final AnalysisTaskRepository taskRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final MatchReportRepository reportRepository;

    public ListAnalysisTasksUseCase(
        AnalysisTaskRepository taskRepository,
        ResumeRepository resumeRepository,
        JobDescriptionRepository jobRepository,
        MatchReportRepository reportRepository
    ) {
        this.taskRepository = taskRepository;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.reportRepository = reportRepository;
    }

    public AnalysisPage list(AnalysisTask.Status status, int page, int size) {
        validatePagination(page, size);
        Pageable pageable = PageRequest.of(page, size, Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id")
        ));
        Page<AnalysisTask> taskPage = status == null
            ? taskRepository.findAll(pageable)
            : taskRepository.findByStatus(status, pageable);

        if (taskPage.isEmpty()) {
            return toPage(taskPage, List.of());
        }

        Set<Long> resumeIds = taskPage.stream()
            .map(AnalysisTask::getResumeId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> jobIds = taskPage.stream()
            .map(AnalysisTask::getJobDescriptionId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> taskIds = taskPage.stream()
            .map(AnalysisTask::getId)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Long, Resume> resumesById = resumeRepository.findAllById(resumeIds).stream()
            .collect(Collectors.toMap(Resume::getId, Function.identity()));
        Map<Long, JobDescription> jobsById = jobRepository.findAllById(jobIds).stream()
            .collect(Collectors.toMap(JobDescription::getId, Function.identity()));
        Map<Long, MatchReport> reportsByTaskId = reportRepository.findAllByTaskIdIn(taskIds).stream()
            .collect(Collectors.toMap(MatchReport::getTaskId, Function.identity()));

        var items = taskPage.stream()
            .map(task -> toListItem(task, resumesById, jobsById, reportsByTaskId))
            .toList();
        return toPage(taskPage, items);
    }

    private AnalysisListItem toListItem(
        AnalysisTask task,
        Map<Long, Resume> resumesById,
        Map<Long, JobDescription> jobsById,
        Map<Long, MatchReport> reportsByTaskId
    ) {
        Resume resume = resumesById.get(task.getResumeId());
        if (resume == null) {
            throw new ResourceNotFoundException("Resume not found");
        }
        JobDescription job = jobsById.get(task.getJobDescriptionId());
        if (job == null) {
            throw new ResourceNotFoundException("Job description not found");
        }
        MatchReport report = reportsByTaskId.get(task.getId());
        return new AnalysisListItem(
            task.getId(),
            job.getTitle(),
            resume.getFileName(),
            task.getStatus(),
            report == null ? null : report.getMatchScore(),
            task.getAttemptCount(),
            task.getMaxAttempts(),
            task.getFailureCode(),
            task.getCreatedAt(),
            task.getUpdatedAt(),
            task.getCompletedAt()
        );
    }

    private AnalysisPage toPage(Page<AnalysisTask> taskPage, List<AnalysisListItem> items) {
        return new AnalysisPage(
            items,
            taskPage.getNumber(),
            taskPage.getSize(),
            taskPage.getTotalElements(),
            taskPage.getTotalPages()
        );
    }

    private void validatePagination(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("Page must not be negative");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("Size must be between 1 and 100");
        }
    }
}
