package com.zhulikang.aimatch.application.resume;

import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UploadResumeUseCaseTest {
    @Test
    void savesPreparedResume() {
        PrepareResumeUseCase prepareResumeUseCase = mock(PrepareResumeUseCase.class);
        ResumeRepository repository = mock(ResumeRepository.class);
        UploadResumeUseCase useCase = new UploadResumeUseCase(prepareResumeUseCase, repository);
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[] {1});
        PreparedResume prepared = new PreparedResume("resume.pdf", "Java Redis", "Java Redis");
        Resume saved = new Resume("resume.pdf", "Java Redis", "Java Redis");
        when(prepareResumeUseCase.prepare(file)).thenReturn(prepared);
        when(repository.save(any(Resume.class))).thenReturn(saved);

        assertThat(useCase.upload(file)).isSameAs(saved);
        verify(prepareResumeUseCase).prepare(file);
        ArgumentCaptor<Resume> resumeCaptor = ArgumentCaptor.forClass(Resume.class);
        verify(repository).save(resumeCaptor.capture());
        assertThat(resumeCaptor.getValue().getFileName()).isEqualTo("resume.pdf");
        assertThat(resumeCaptor.getValue().getRawText()).isEqualTo("Java Redis");
        assertThat(resumeCaptor.getValue().getStructuredSummary()).isEqualTo("Java Redis");
    }

    @Test
    void doesNotSaveWhenPreparationFails() {
        PrepareResumeUseCase prepareResumeUseCase = mock(PrepareResumeUseCase.class);
        ResumeRepository repository = mock(ResumeRepository.class);
        UploadResumeUseCase useCase = new UploadResumeUseCase(prepareResumeUseCase, repository);
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[] {1});
        when(prepareResumeUseCase.prepare(file))
            .thenThrow(new IllegalArgumentException("Resume text must not be blank"));

        assertThatThrownBy(() -> useCase.upload(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Resume text must not be blank");
        verifyNoInteractions(repository);
    }
}
