package com.zhulikang.aimatch.application.resume;

import com.zhulikang.aimatch.document.DocumentTextExtractor;
import com.zhulikang.aimatch.document.ResumeFileValidator;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PrepareResumeUseCase {
    private final ResumeFileValidator resumeFileValidator;
    private final DocumentTextExtractor extractor;

    public PrepareResumeUseCase(ResumeFileValidator resumeFileValidator, DocumentTextExtractor extractor) {
        this.resumeFileValidator = resumeFileValidator;
        this.extractor = extractor;
    }

    public PreparedResume prepare(MultipartFile file) {
        resumeFileValidator.validate(file);
        String rawText = extractor.extract(file);
        if (rawText.isBlank()) {
            throw new IllegalArgumentException("Resume text must not be blank");
        }
        return new PreparedResume(file.getOriginalFilename(), rawText, rawText);
    }
}
