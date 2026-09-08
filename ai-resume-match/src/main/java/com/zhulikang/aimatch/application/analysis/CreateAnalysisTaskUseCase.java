package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.api.ResourceNotFoundException;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.ResumeRepository;
import com.zhulikang.aimatch.security.RequestIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateAnalysisTaskUseCase {
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final AnalysisTaskCreator taskCreator;

    public CreateAnalysisTaskUseCase(
        ResumeRepository resumeRepository,
        JobDescriptionRepository jobRepository,
        AnalysisTaskCreator taskCreator
    ) {
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.taskCreator = taskCreator;
    }

    @Transactional
    public AnalysisTask create(Long resumeId, Long jobDescriptionId) {
        String ownerId = RequestIdentity.currentOwnerId();
        boolean resumeExists = resumeRepository.existsByIdAndOwnerId(resumeId, ownerId);
        if (!resumeExists) {
            throw new ResourceNotFoundException("Resume not found");
        }
        boolean jobExists = jobRepository.existsByIdAndOwnerId(jobDescriptionId, ownerId);
        if (!jobExists) {
            throw new ResourceNotFoundException("Job description not found");
        }
        return taskCreator.create(resumeId, jobDescriptionId);
    }
}
