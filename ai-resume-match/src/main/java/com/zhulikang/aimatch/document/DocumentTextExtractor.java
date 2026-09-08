package com.zhulikang.aimatch.document;

import com.zhulikang.aimatch.config.ResumeProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DocumentTextExtractor {
    private final int maxPdfPages;

    public DocumentTextExtractor() {
        this(50);
    }

    @Autowired
    public DocumentTextExtractor(ResumeProperties properties) {
        this(properties.upload().maxPdfPages());
    }

    DocumentTextExtractor(int maxPdfPages) {
        if (maxPdfPages < 1) {
            throw new IllegalArgumentException("PDF page limit must be positive");
        }
        this.maxPdfPages = maxPdfPages;
    }

    public String extract(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            if (name.endsWith(".pdf")) {
                return normalize(extractPdf(file));
            }
            if (name.endsWith(".docx")) {
                return normalize(extractDocx(file));
            }
            throw new IllegalArgumentException("Only PDF and DOCX are supported");
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to extract document text", e);
        }
    }

    String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .map(line -> line.replaceAll("[\\p{Zs}\\t\\f]+", " ").trim())
            .filter(line -> !line.isBlank())
            .collect(Collectors.joining("\n"));
    }

    private String extractPdf(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            if (document.getNumberOfPages() > maxPdfPages) {
                throw new IllegalArgumentException("PDF exceeds the configured page limit");
            }
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractDocx(MultipartFile file) throws IOException {
        try (XWPFDocument document = new XWPFDocument(file.getInputStream())) {
            List<String> parts = new ArrayList<>();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    parts.add(paragraph.getText());
                } else if (element instanceof XWPFTable table) {
                    table.getRows().forEach(row -> parts.add(
                        row.getTableCells().stream()
                            .map(cell -> cell.getText().trim())
                            .filter(value -> !value.isBlank())
                            .collect(Collectors.joining(" | "))
                    ));
                }
            }
            return String.join("\n", parts);
        }
    }
}
