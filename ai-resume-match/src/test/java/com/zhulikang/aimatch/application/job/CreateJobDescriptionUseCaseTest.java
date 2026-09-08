package com.zhulikang.aimatch.application.job;

import com.zhulikang.aimatch.job.JdTagExtractor;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.support.RequestOwnerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(RequestOwnerExtension.class)
class CreateJobDescriptionUseCaseTest {
    @Test
    void extractsTagsSerializesThemAndSavesJobDescription() {
        JdTagExtractor tagExtractor = mock(JdTagExtractor.class);
        JobDescriptionRepository repository = mock(JobDescriptionRepository.class);
        CreateJobDescriptionUseCase useCase = new CreateJobDescriptionUseCase(
            new JobTitleNormalizer(),
            tagExtractor,
            repository
        );
        when(tagExtractor.extractTags("Java Redis")).thenReturn(List.of("Java", "Redis"));
        when(tagExtractor.toStorageValue(List.of("Java", "Redis"))).thenReturn("Java,Redis");
        when(repository.save(any(JobDescription.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobDescription saved = useCase.create("  Backend Engineer  ", "Java Redis");

        assertThat(saved.getTitle()).isEqualTo("Backend Engineer");
        assertThat(saved.getContent()).isEqualTo("Java Redis");
        assertThat(saved.getSkillTags()).isEqualTo("Java,Redis");
        verify(tagExtractor).extractTags("Java Redis");
        verify(tagExtractor).toStorageValue(List.of("Java", "Redis"));
        verify(repository).save(any(JobDescription.class));
    }
}
