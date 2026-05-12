package com.zhulikang.aimatch.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.stream.Collectors;

@Service
public class DocumentTextExtractor {
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
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String extractPdf(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractDocx(MultipartFile file) throws IOException {
        try (XWPFDocument document = new XWPFDocument(file.getInputStream())) {
            return document.getParagraphs().stream()
                .map(paragraph -> paragraph.getText())
                .collect(Collectors.joining(" "));
        }
    }
}
