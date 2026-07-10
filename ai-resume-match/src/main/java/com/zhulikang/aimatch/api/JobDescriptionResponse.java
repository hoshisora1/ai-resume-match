package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.job.JobDescription;

public record JobDescriptionResponse(Long jobDescriptionId, String title) {
    public static JobDescriptionResponse from(JobDescription job) {
        return new JobDescriptionResponse(job.getId(), job.getTitle());
    }
}
