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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class GetAnalysisTaskUseCase {
    private final AnalysisTaskRepository taskRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final MatchReportRepository reportRepository;

    public GetAnalysisTaskUseCase(
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
    public Optional<AnalysisTaskDetails> find(Long taskId) {
        return taskRepository.findById(taskId).map(this::toDetails);
    }

    private AnalysisTaskDetails toDetails(AnalysisTask task) {
        ResumeDisplayView resume = resumeRepository.findDisplayViewById(task.getResumeId())
            .orElseThrow(() -> new ResourceNotFoundException("Resume not found"));
        JobDescriptionDisplayView job = jobRepository.findDisplayViewById(task.getJobDescriptionId())
            .orElseThrow(() -> new ResourceNotFoundException("Job description not found"));
        Integer matchScore = reportRepository.findScoreViewByTaskId(task.getId())
            .map(MatchScoreView::matchScore)
            .orElse(null);
        return new AnalysisTaskDetails(task, job.title(), resume.fileName(), matchScore);
    }
}
