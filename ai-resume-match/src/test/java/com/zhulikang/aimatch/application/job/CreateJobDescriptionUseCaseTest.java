package com.zhulikang.aimatch.application.job;

import com.zhulikang.aimatch.job.JdTagExtractor;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateJobDescriptionUseCaseTest {
    @Test
    void extractsTagsSerializesThemAndSavesJobDescription() {
        JdTagExtractor tagExtractor = mock(JdTagExtractor.class);
        JobDescriptionRepository repository = mock(JobDescriptionRepository.class);
        CreateJobDescriptionUseCase useCase = new CreateJobDescriptionUseCase(tagExtractor, repository);
        JobDescription saved = new JobDescription("Java Redis", "Java,Redis");
        when(tagExtractor.extractTags("Java Redis")).thenReturn(List.of("Java", "Redis"));
        when(tagExtractor.toStorageValue(List.of("Java", "Redis"))).thenReturn("Java,Redis");
        when(repository.save(any(JobDescription.class))).thenReturn(saved);

        assertThat(useCase.create("Java Redis")).isSameAs(saved);
        verify(tagExtractor).extractTags("Java Redis");
        verify(tagExtractor).toStorageValue(List.of("Java", "Redis"));
        verify(repository).save(any(JobDescription.class));
    }
}
