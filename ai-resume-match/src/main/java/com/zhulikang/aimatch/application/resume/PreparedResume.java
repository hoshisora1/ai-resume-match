package com.zhulikang.aimatch.application.resume;

import com.zhulikang.aimatch.resume.Resume;

public record PreparedResume(String fileName, String rawText, String structuredSummary) {
    public Resume toEntity() {
        return new Resume(fileName, rawText, structuredSummary);
    }
}
