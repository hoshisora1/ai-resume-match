package com.zhulikang.aimatch.application.resume;

import com.zhulikang.aimatch.document.DocumentTextExtractor;
import com.zhulikang.aimatch.document.ResumeFileValidator;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UploadResumeUseCase {
    private final ResumeFileValidator resumeFileValidator;
    private final DocumentTextExtractor extractor;
    private final ResumeRepository resumeRepository;

    public UploadResumeUseCase(
        ResumeFileValidator resumeFileValidator,
        DocumentTextExtractor extractor,
        ResumeRepository resumeRepository
    ) {
        this.resumeFileValidator = resumeFileValidator;
        this.extractor = extractor;
        this.resumeRepository = resumeRepository;
    }

    public Resume upload(MultipartFile file) {
        resumeFileValidator.validate(file);
        String rawText = extractor.extract(file);
        if (rawText.isBlank()) {
            throw new IllegalArgumentException("Resume text must not be blank");
        }
        return resumeRepository.save(new Resume(file.getOriginalFilename(), rawText, rawText));
    }
}
