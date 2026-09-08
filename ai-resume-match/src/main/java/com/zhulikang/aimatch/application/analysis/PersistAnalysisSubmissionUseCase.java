package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.application.job.CreateJobDescriptionUseCase;
import com.zhulikang.aimatch.application.resume.PreparedResume;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import com.zhulikang.aimatch.security.RequestIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersistAnalysisSubmissionUseCase {
    private final ResumeRepository resumeRepository;
    private final CreateJobDescriptionUseCase createJobDescriptionUseCase;
    private final AnalysisTaskCreator taskCreator;

    public PersistAnalysisSubmissionUseCase(
        ResumeRepository resumeRepository,
        CreateJobDescriptionUseCase createJobDescriptionUseCase,
        AnalysisTaskCreator taskCreator
    ) {
        this.resumeRepository = resumeRepository;
        this.createJobDescriptionUseCase = createJobDescriptionUseCase;
        this.taskCreator = taskCreator;
    }

    @Transactional
    public AnalysisSubmission persist(
        PreparedResume preparedResume,
        String jobTitle,
        String jobContent
    ) {
        Resume resume = resumeRepository.save(preparedResume.toEntity(RequestIdentity.currentOwnerId()));
        JobDescription job = createJobDescriptionUseCase.create(jobTitle, jobContent);
        AnalysisTask task = taskCreator.create(resume.getId(), job.getId());
        return new AnalysisSubmission(task, job.getTitle(), resume.getFileName());
    }
}
