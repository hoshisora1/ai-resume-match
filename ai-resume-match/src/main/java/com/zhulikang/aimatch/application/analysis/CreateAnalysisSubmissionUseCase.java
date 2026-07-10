package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.application.resume.PrepareResumeUseCase;
import com.zhulikang.aimatch.application.resume.PreparedResume;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CreateAnalysisSubmissionUseCase {
    private final PrepareResumeUseCase prepareResumeUseCase;
    private final PersistAnalysisSubmissionUseCase persistUseCase;

    public CreateAnalysisSubmissionUseCase(
        PrepareResumeUseCase prepareResumeUseCase,
        PersistAnalysisSubmissionUseCase persistUseCase
    ) {
        this.prepareResumeUseCase = prepareResumeUseCase;
        this.persistUseCase = persistUseCase;
    }

    public AnalysisSubmission create(MultipartFile file, String jobTitle, String jobContent) {
        PreparedResume preparedResume = prepareResumeUseCase.prepare(file);
        return persistUseCase.persist(preparedResume, jobTitle, jobContent);
    }
}
