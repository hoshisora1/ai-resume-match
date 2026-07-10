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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional(readOnly = true)
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

        Map<Long, ResumeDisplayView> resumesById = resumeRepository.findDisplayViewsByIdIn(resumeIds).stream()
            .collect(Collectors.toMap(ResumeDisplayView::id, Function.identity()));
        Map<Long, JobDescriptionDisplayView> jobsById = jobRepository.findDisplayViewsByIdIn(jobIds).stream()
            .collect(Collectors.toMap(JobDescriptionDisplayView::id, Function.identity()));
        Map<Long, MatchScoreView> scoresByTaskId = reportRepository.findScoreViewsByTaskIdIn(taskIds).stream()
            .collect(Collectors.toMap(MatchScoreView::taskId, Function.identity()));

        var items = taskPage.stream()
            .map(task -> toListItem(task, resumesById, jobsById, scoresByTaskId))
            .toList();
        return toPage(taskPage, items);
    }

    private AnalysisListItem toListItem(
        AnalysisTask task,
        Map<Long, ResumeDisplayView> resumesById,
        Map<Long, JobDescriptionDisplayView> jobsById,
        Map<Long, MatchScoreView> scoresByTaskId
    ) {
        ResumeDisplayView resume = resumesById.get(task.getResumeId());
        if (resume == null) {
            throw new ResourceNotFoundException("Resume not found");
        }
        JobDescriptionDisplayView job = jobsById.get(task.getJobDescriptionId());
        if (job == null) {
            throw new ResourceNotFoundException("Job description not found");
        }
        MatchScoreView score = scoresByTaskId.get(task.getId());
        return new AnalysisListItem(
            task.getId(),
            job.title(),
            resume.fileName(),
            task.getStatus(),
            score == null ? null : score.matchScore(),
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
