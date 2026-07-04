package com.zhulikang.aimatch.document;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ResumeFileValidator {
    public void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Resume file must not be empty");
        }
    }
}
