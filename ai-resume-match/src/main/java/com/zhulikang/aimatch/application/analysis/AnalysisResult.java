package com.zhulikang.aimatch.application.analysis;

public record AnalysisResult(int matchScore, String reportContent) {
    public AnalysisResult {
        if (matchScore < 0 || matchScore > 100) {
            throw new IllegalArgumentException("Match score must be between 0 and 100");
        }
        if (reportContent == null || reportContent.isBlank()) {
            throw new IllegalArgumentException("Analysis report must not be blank");
        }
    }
}
