package com.zhulikang.aimatch.document;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeFileValidatorTest {
    @Test
    void rejectsEmptyFile() {
        ResumeFileValidator validator = new ResumeFileValidator(DataSize.ofMegabytes(5));
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> validator.validate(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Resume file must not be empty");
    }

    @Test
    void rejectsFileLargerThanLimit() {
        ResumeFileValidator validator = new ResumeFileValidator(DataSize.ofBytes(5));
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "resume.pdf",
            "application/pdf",
            new byte[] {1, 2, 3, 4, 5, 6}
        );

        assertThatThrownBy(() -> validator.validate(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Resume file exceeds the configured maximum size");
    }

    @Test
    void rejectsUnsupportedExtension() {
        ResumeFileValidator validator = new ResumeFileValidator(DataSize.ofMegabytes(5));
        MockMultipartFile file = new MockMultipartFile("file", "resume.txt", "text/plain", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> validator.validate(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Only PDF and DOCX are supported");
    }

    @Test
    void acceptsPdfAndDocxExtensionsCaseInsensitively() {
        ResumeFileValidator validator = new ResumeFileValidator(DataSize.ofMegabytes(5));
        MockMultipartFile pdfFile = new MockMultipartFile("file", "resume.PDF", "application/pdf", new byte[] {1, 2, 3});
        MockMultipartFile docxFile = new MockMultipartFile(
            "file",
            "resume.DoCx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            new byte[] {1, 2, 3}
        );

        assertThatCode(() -> validator.validate(pdfFile)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(docxFile)).doesNotThrowAnyException();
    }
}
