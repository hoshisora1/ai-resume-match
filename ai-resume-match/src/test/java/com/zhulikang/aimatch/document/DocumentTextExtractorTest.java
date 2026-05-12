package com.zhulikang.aimatch.document;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTextExtractorTest {
    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void rejectsUnsupportedFileType() {
        MockMultipartFile file = new MockMultipartFile("file", "resume.txt", "text/plain", "hello".getBytes());

        assertThatThrownBy(() -> extractor.extract(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Only PDF and DOCX are supported");
    }

    @Test
    void normalizesBlankCharacters() {
        String normalized = extractor.normalize("Java\n\n Spring   Boot\tRedis");

        assertThat(normalized).isEqualTo("Java Spring Boot Redis");
    }
}
