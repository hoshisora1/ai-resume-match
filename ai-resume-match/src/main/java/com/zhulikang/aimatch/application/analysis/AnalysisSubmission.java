package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;

public record AnalysisSubmission(
    AnalysisTask task,
    String jobTitle,
    String resumeFileName
) {
}
