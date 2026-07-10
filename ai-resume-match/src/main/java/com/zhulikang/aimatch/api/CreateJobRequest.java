package com.zhulikang.aimatch.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateJobRequest(
    @Size(max = 120) String title,
    @NotBlank String content
) {
}
