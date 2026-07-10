package com.zhulikang.aimatch.application.job;

import org.springframework.stereotype.Component;

@Component
public class JobTitleNormalizer {
    static final int MAX_LENGTH = 120;

    public String normalize(String explicitTitle, String content) {
        String candidate = firstNonBlank(explicitTitle, content);
        if (candidate == null) {
            return "未命名岗位";
        }
        String normalized = candidate.strip();
        return normalized.codePointCount(0, normalized.length()) <= MAX_LENGTH
            ? normalized
            : normalized.substring(0, normalized.offsetByCodePoints(0, MAX_LENGTH));
    }

    private String firstNonBlank(String explicitTitle, String content) {
        if (explicitTitle != null && !explicitTitle.isBlank()) {
            return explicitTitle;
        }
        if (content == null) {
            return null;
        }
        return content.lines()
            .map(String::strip)
            .filter(line -> !line.isBlank())
            .findFirst()
            .orElse(null);
    }
}
