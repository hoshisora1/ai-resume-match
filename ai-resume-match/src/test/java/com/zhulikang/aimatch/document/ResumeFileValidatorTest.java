package com.zhulikang.aimatch.document;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    void acceptsPdfAndDocxContentCaseInsensitively() throws IOException {
        ResumeFileValidator validator = new ResumeFileValidator(DataSize.ofMegabytes(5));
        MockMultipartFile pdfFile = new MockMultipartFile(
            "file",
            "resume.PDF",
            "application/pdf",
            "%PDF-1.7\n%%EOF".getBytes()
        );
        MockMultipartFile docxFile = new MockMultipartFile(
            "file",
            "resume.DoCx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            docx()
        );

        assertThatCode(() -> validator.validate(pdfFile)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(docxFile)).doesNotThrowAnyException();
    }

    @Test
    void rejectsExtensionSpoofing() {
        ResumeFileValidator validator = new ResumeFileValidator(DataSize.ofMegabytes(5));
        MockMultipartFile fakePdf = new MockMultipartFile(
            "file",
            "resume.pdf",
            "application/pdf",
            "not a pdf".getBytes()
        );
        MockMultipartFile fakeDocx = new MockMultipartFile(
            "file",
            "resume.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "%PDF-1.7".getBytes()
        );

        assertThatThrownBy(() -> validator.validate(fakePdf))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("File content does not match the PDF extension");
        assertThatThrownBy(() -> validator.validate(fakeDocx))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("File content does not match the DOCX extension");
    }

    @Test
    void rejectsDocxThatExceedsInflatedEntryLimit() throws IOException {
        ResumeFileValidator validator = new ResumeFileValidator(
            DataSize.ofMegabytes(5),
            10,
            DataSize.ofBytes(32),
            DataSize.ofBytes(64)
        );
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "resume.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            oversizedDocxEntry()
        );

        assertThatThrownBy(() -> validator.validate(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("DOCX archive exceeds configured resource limits");
    }

    @Test
    void rejectsZipWithoutRequiredWordParts() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry("unrelated.txt"));
            archive.write("hello".getBytes());
        }
        MockMultipartFile file = new MockMultipartFile("file", "resume.docx", null, output.toByteArray());

        assertThatThrownBy(() -> new ResumeFileValidator(DataSize.ofMegabytes(5)).validate(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("DOCX archive is not a valid Word document");
    }

    private byte[] docx() throws IOException {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Java");
            document.write(output);
            return output.toByteArray();
        }
    }

    private byte[] oversizedDocxEntry() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry("[Content_Types].xml"));
            archive.write("types".getBytes());
            archive.closeEntry();
            archive.putNextEntry(new ZipEntry("word/document.xml"));
            archive.write("x".repeat(33).getBytes());
        }
        return output.toByteArray();
    }
}
