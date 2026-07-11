package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.api.validation.UnicodeNotBlank;
import org.hibernate.validator.constraints.CodePointLength;

import static com.zhulikang.aimatch.api.validation.JobDescriptionConstraints.CONTENT_MAX_CODE_POINTS;
import static com.zhulikang.aimatch.api.validation.JobDescriptionConstraints.TITLE_MAX_CODE_POINTS;

public record CreateJobRequest(
    @CodePointLength(max = TITLE_MAX_CODE_POINTS) String title,
    @UnicodeNotBlank @CodePointLength(max = CONTENT_MAX_CODE_POINTS) String content
) {
}
