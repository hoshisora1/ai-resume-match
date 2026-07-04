package com.zhulikang.aimatch.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;

@Component
public class ResumeFileValidator {
    private final DataSize maxFileSize;

    public ResumeFileValidator(@Value("${resume.upload.max-file-size:5MB}") DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Resume file must not be empty");
        }
        if (file.getSize() > maxFileSize.toBytes()) {
            throw new IllegalArgumentException("Resume file exceeds the configured maximum size");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".pdf") && !name.endsWith(".docx")) {
            throw new IllegalArgumentException("Only PDF and DOCX are supported");
        }
    }
}
