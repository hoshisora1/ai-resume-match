package com.zhulikang.aimatch.api;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

public record CreateJobRequest(
    @CodePointLength(max = 120) String title,
    @NotBlank String content
) {
}
