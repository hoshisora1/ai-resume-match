package com.zhulikang.aimatch.api;

import jakarta.validation.constraints.NotNull;

public record CreateAnalysisRequest(
    @NotNull Long resumeId,
    @NotNull Long jobDescriptionId
) {
}
