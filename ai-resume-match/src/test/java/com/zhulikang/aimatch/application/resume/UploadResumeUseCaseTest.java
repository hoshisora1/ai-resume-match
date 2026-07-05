package com.zhulikang.aimatch.application.resume;

import com.zhulikang.aimatch.document.DocumentTextExtractor;
import com.zhulikang.aimatch.document.ResumeFileValidator;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadResumeUseCaseTest {
    @Test
    void validatesBeforeExtractingAndSavingResume() {
        ResumeFileValidator validator = mock(ResumeFileValidator.class);
        DocumentTextExtractor extractor = mock(DocumentTextExtractor.class);
        ResumeRepository repository = mock(ResumeRepository.class);
        UploadResumeUseCase useCase = new UploadResumeUseCase(validator, extractor, repository);
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[] {1});
        Resume saved = new Resume("resume.pdf", "Java Redis", "Java Redis");
        when(extractor.extract(file)).thenReturn("Java Redis");
        when(repository.save(any(Resume.class))).thenReturn(saved);

        assertThat(useCase.upload(file)).isSameAs(saved);
        verify(validator).validate(file);
        verify(repository).save(any(Resume.class));
    }

    @Test
    void rejectsBlankExtractedText() {
        ResumeFileValidator validator = mock(ResumeFileValidator.class);
        DocumentTextExtractor extractor = mock(DocumentTextExtractor.class);
        ResumeRepository repository = mock(ResumeRepository.class);
        UploadResumeUseCase useCase = new UploadResumeUseCase(validator, extractor, repository);
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[] {1});
        when(extractor.extract(file)).thenReturn("   ");

        assertThatThrownBy(() -> useCase.upload(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Resume text must not be blank");
    }
}
