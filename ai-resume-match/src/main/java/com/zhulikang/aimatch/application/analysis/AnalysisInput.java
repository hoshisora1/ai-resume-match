package com.zhulikang.aimatch.application.analysis;

import java.util.List;

public record AnalysisInput(
    Long taskId,
    String resumeText,
    String jobTitle,
    String jobDescription,
    List<String> skillTags,
    String correlationId
) {
    public AnalysisInput {
        skillTags = List.copyOf(skillTags);
    }
}
