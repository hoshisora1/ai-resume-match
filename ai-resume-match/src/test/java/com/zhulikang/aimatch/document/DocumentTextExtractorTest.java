package com.zhulikang.aimatch.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

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

        assertThat(normalized).isEqualTo("Java\nSpring Boot Redis");
    }

    @Test
    void extractsParagraphsAndTablesFromDocx() throws IOException {
        byte[] content;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Backend engineer");
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Skill");
            table.getRow(0).getCell(1).setText("Years");
            table.getRow(1).getCell(0).setText("Java");
            table.getRow(1).getCell(1).setText("5");
            document.write(output);
            content = output.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile("file", "resume.docx", null, content);

        assertThat(extractor.extract(file))
            .isEqualTo("Backend engineer\nSkill | Years\nJava | 5");
    }

    @Test
    void rejectsPdfBeyondConfiguredPageLimit() throws IOException {
        byte[] content;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(output);
            content = output.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", null, content);

        assertThatThrownBy(() -> new DocumentTextExtractor(1).extract(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("PDF exceeds the configured page limit");
    }
}
