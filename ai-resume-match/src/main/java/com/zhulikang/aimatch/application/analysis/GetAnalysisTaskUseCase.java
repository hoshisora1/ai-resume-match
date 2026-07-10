package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.analysis.MatchReportRepository;
import com.zhulikang.aimatch.api.ResourceNotFoundException;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.springframework.stereotype.Service;

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

    public Optional<AnalysisTaskDetails> find(Long taskId) {
        return taskRepository.findById(taskId).map(this::toDetails);
    }

    private AnalysisTaskDetails toDetails(AnalysisTask task) {
        Resume resume = resumeRepository.findById(task.getResumeId())
            .orElseThrow(() -> new ResourceNotFoundException("Resume not found"));
        JobDescription job = jobRepository.findById(task.getJobDescriptionId())
            .orElseThrow(() -> new ResourceNotFoundException("Job description not found"));
        Integer matchScore = reportRepository.findByTaskId(task.getId())
            .map(report -> report.getMatchScore())
            .orElse(null);
        return new AnalysisTaskDetails(task, job.getTitle(), resume.getFileName(), matchScore);
    }
}
