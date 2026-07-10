package com.zhulikang.aimatch.application.job;

import com.zhulikang.aimatch.job.JdTagExtractor;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import org.springframework.stereotype.Service;

@Service
public class CreateJobDescriptionUseCase {
    private final JobTitleNormalizer jobTitleNormalizer;
    private final JdTagExtractor jdTagExtractor;
    private final JobDescriptionRepository jobRepository;

    public CreateJobDescriptionUseCase(
        JobTitleNormalizer jobTitleNormalizer,
        JdTagExtractor jdTagExtractor,
        JobDescriptionRepository jobRepository
    ) {
        this.jobTitleNormalizer = jobTitleNormalizer;
        this.jdTagExtractor = jdTagExtractor;
        this.jobRepository = jobRepository;
    }

    public JobDescription create(String title, String content) {
        String normalizedTitle = jobTitleNormalizer.normalize(title, content);
        String tags = jdTagExtractor.toStorageValue(jdTagExtractor.extractTags(content));
        return jobRepository.save(new JobDescription(normalizedTitle, content, tags));
    }
}
