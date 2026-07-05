package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.api.ResourceNotFoundException;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateAnalysisTaskUseCase {
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final AnalysisTaskRepository taskRepository;
    private final AnalysisTaskPublisher publisher;
    private final AnalysisMetrics metrics;

    public CreateAnalysisTaskUseCase(
        ResumeRepository resumeRepository,
        JobDescriptionRepository jobRepository,
        AnalysisTaskRepository taskRepository,
        AnalysisTaskPublisher publisher,
        AnalysisMetrics metrics
    ) {
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.taskRepository = taskRepository;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    @Transactional
    public AnalysisTask create(Long resumeId, Long jobDescriptionId) {
        if (!resumeRepository.existsById(resumeId)) {
            throw new ResourceNotFoundException("Resume not found");
        }
        if (!jobRepository.existsById(jobDescriptionId)) {
            throw new ResourceNotFoundException("Job description not found");
        }
        AnalysisTask task = taskRepository.save(new AnalysisTask(resumeId, jobDescriptionId));
        publisher.publishAfterCommit(task.getId());
        metrics.taskCreated();
        return task;
    }
}
