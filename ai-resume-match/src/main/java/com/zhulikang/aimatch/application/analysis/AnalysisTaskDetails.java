package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;

public record AnalysisTaskDetails(
    AnalysisTask task,
    String jobTitle,
    String resumeFileName,
    Integer matchScore
) {
}
