package com.zhulikang.aimatch.application.resume;

import com.zhulikang.aimatch.resume.Resume;

public record PreparedResume(String fileName, String rawText) {
    public Resume toEntity() {
        return new Resume(fileName, rawText);
    }

    public Resume toEntity(String ownerId) {
        return new Resume(ownerId, fileName, rawText);
    }
}
