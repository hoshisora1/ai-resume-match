package com.zhulikang.aimatch.application.resume;

import com.zhulikang.aimatch.document.DocumentTextExtractor;
import com.zhulikang.aimatch.document.ResumeFileValidator;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PrepareResumeUseCase {
    static final int MAX_TEXT_CODE_POINTS = 200_000;

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
        if (rawText.codePointCount(0, rawText.length()) > MAX_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException("Extracted resume text exceeds the supported length");
        }
        return new PreparedResume(file.getOriginalFilename(), rawText);
    }
}
