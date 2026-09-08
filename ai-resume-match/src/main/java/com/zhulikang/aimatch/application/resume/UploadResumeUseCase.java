package com.zhulikang.aimatch.application.resume;

import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import com.zhulikang.aimatch.security.RequestIdentity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UploadResumeUseCase {
    private final PrepareResumeUseCase prepareResumeUseCase;
    private final ResumeRepository resumeRepository;

    public UploadResumeUseCase(
        PrepareResumeUseCase prepareResumeUseCase,
        ResumeRepository resumeRepository
    ) {
        this.prepareResumeUseCase = prepareResumeUseCase;
        this.resumeRepository = resumeRepository;
    }

    public Resume upload(MultipartFile file) {
        return resumeRepository.save(prepareResumeUseCase.prepare(file).toEntity(RequestIdentity.currentOwnerId()));
    }
}
