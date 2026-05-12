package com.zhulikang.aimatch.api;

import jakarta.validation.constraints.NotBlank;

public record CreateJobRequest(@NotBlank String content) {
}
