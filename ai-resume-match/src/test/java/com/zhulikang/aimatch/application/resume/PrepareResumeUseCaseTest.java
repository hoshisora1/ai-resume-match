package com.zhulikang.aimatch.application.resume;

import com.zhulikang.aimatch.document.DocumentTextExtractor;
import com.zhulikang.aimatch.document.ResumeFileValidator;
import com.zhulikang.aimatch.resume.Resume;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrepareResumeUseCaseTest {
    @Test
    void validatesAndExtractsResumeWithoutPersistingIt() {
        ResumeFileValidator validator = mock(ResumeFileValidator.class);
        DocumentTextExtractor extractor = mock(DocumentTextExtractor.class);
        PrepareResumeUseCase useCase = new PrepareResumeUseCase(validator, extractor);
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "resume.pdf",
            "application/pdf",
            new byte[] {1}
        );
        when(extractor.extract(file)).thenReturn("Java Redis");

        PreparedResume prepared = useCase.prepare(file);

        assertThat(prepared.fileName()).isEqualTo("resume.pdf");
        assertThat(prepared.rawText()).isEqualTo("Java Redis");
        Resume entity = prepared.toEntity();
        assertThat(entity.getId()).isNull();
        assertThat(entity.getFileName()).isEqualTo("resume.pdf");
        assertThat(entity.getRawText()).isEqualTo("Java Redis");
        InOrder order = inOrder(validator, extractor);
        order.verify(validator).validate(file);
        order.verify(extractor).extract(file);
    }

    @Test
    void rejectsBlankExtractedText() {
        ResumeFileValidator validator = mock(ResumeFileValidator.class);
        DocumentTextExtractor extractor = mock(DocumentTextExtractor.class);
        PrepareResumeUseCase useCase = new PrepareResumeUseCase(validator, extractor);
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "resume.pdf",
            "application/pdf",
            new byte[] {1}
        );
        when(extractor.extract(file)).thenReturn("   ");

        assertThatThrownBy(() -> useCase.prepare(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Resume text must not be blank");

        verify(validator).validate(file);
        verify(extractor).extract(file);
    }

    @Test
    void rejectsExtractedTextThatExceedsTheAgentContract() {
        ResumeFileValidator validator = mock(ResumeFileValidator.class);
        DocumentTextExtractor extractor = mock(DocumentTextExtractor.class);
        PrepareResumeUseCase useCase = new PrepareResumeUseCase(validator, extractor);
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "resume.pdf",
            "application/pdf",
            new byte[] {1}
        );
        when(extractor.extract(file)).thenReturn("a".repeat(PrepareResumeUseCase.MAX_TEXT_CODE_POINTS + 1));

        assertThatThrownBy(() -> useCase.prepare(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Extracted resume text exceeds the supported length");

        verify(validator).validate(file);
        verify(extractor).extract(file);
    }
}
