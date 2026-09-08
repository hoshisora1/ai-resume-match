package com.zhulikang.aimatch.document;

import com.zhulikang.aimatch.config.ResumeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class ResumeFileValidator {
    private static final int PDF_HEADER_SCAN_BYTES = 1_024;
    private static final int BUFFER_SIZE = 8_192;

    private final DataSize maxFileSize;
    private final int maxDocxEntries;
    private final long maxDocxEntryBytes;
    private final long maxDocxUncompressedBytes;

    @Autowired
    public ResumeFileValidator(ResumeProperties properties) {
        this(
            properties.upload().maxFileSize(),
            properties.upload().maxDocxEntries(),
            properties.upload().maxDocxEntrySize(),
            properties.upload().maxDocxUncompressedSize()
        );
    }

    ResumeFileValidator(
        DataSize maxFileSize,
        int maxDocxEntries,
        DataSize maxDocxEntrySize,
        DataSize maxDocxUncompressedSize
    ) {
        if (maxFileSize.toBytes() <= 0
            || maxDocxEntries < 1
            || maxDocxEntrySize.toBytes() <= 0
            || maxDocxUncompressedSize.toBytes() <= 0) {
            throw new IllegalArgumentException("Resume upload limits must be positive");
        }
        this.maxFileSize = maxFileSize;
        this.maxDocxEntries = maxDocxEntries;
        this.maxDocxEntryBytes = maxDocxEntrySize.toBytes();
        this.maxDocxUncompressedBytes = maxDocxUncompressedSize.toBytes();
    }

    public ResumeFileValidator(DataSize maxFileSize) {
        this(
            maxFileSize,
            512,
            DataSize.ofMegabytes(10),
            DataSize.ofMegabytes(20)
        );
    }

    public void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Resume file must not be empty");
        }
        if (file.getSize() > maxFileSize.toBytes()) {
            throw new IllegalArgumentException("Resume file exceeds the configured maximum size");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".pdf") && !name.endsWith(".docx")) {
            throw new IllegalArgumentException("Only PDF and DOCX are supported");
        }
        try {
            byte[] content = file.getBytes();
            if (name.endsWith(".pdf")) {
                validatePdfSignature(content);
            } else {
                validateDocxArchive(content);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to inspect resume file", ex);
        }
    }

    private void validatePdfSignature(byte[] content) {
        byte[] signature = {'%', 'P', 'D', 'F', '-'};
        int limit = Math.min(content.length - signature.length, PDF_HEADER_SCAN_BYTES - signature.length);
        for (int offset = 0; offset <= limit; offset++) {
            boolean matches = true;
            for (int index = 0; index < signature.length; index++) {
                if (content[offset + index] != signature[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return;
            }
        }
        throw new IllegalArgumentException("File content does not match the PDF extension");
    }

    private void validateDocxArchive(byte[] content) throws IOException {
        if (content.length < 4 || content[0] != 'P' || content[1] != 'K'
            || content[2] != 3 || content[3] != 4) {
            throw new IllegalArgumentException("File content does not match the DOCX extension");
        }

        int entryCount = 0;
        long totalBytes = 0;
        boolean hasContentTypes = false;
        boolean hasDocument = false;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > maxDocxEntries) {
                    throw resourceLimitExceeded();
                }
                String entryName = entry.getName().replace('\\', '/');
                if (entryName.startsWith("/") || entryName.contains("../")) {
                    throw new IllegalArgumentException("DOCX archive contains an invalid entry path");
                }
                hasContentTypes |= "[Content_Types].xml".equals(entryName);
                hasDocument |= "word/document.xml".equals(entryName);

                long entryBytes = 0;
                int read;
                while ((read = archive.read(buffer)) != -1) {
                    entryBytes += read;
                    totalBytes += read;
                    if (entryBytes > maxDocxEntryBytes || totalBytes > maxDocxUncompressedBytes) {
                        throw resourceLimitExceeded();
                    }
                }
                archive.closeEntry();
            }
        }
        if (!hasContentTypes || !hasDocument) {
            throw new IllegalArgumentException("DOCX archive is not a valid Word document");
        }
    }

    private IllegalArgumentException resourceLimitExceeded() {
        return new IllegalArgumentException("DOCX archive exceeds configured resource limits");
    }
}
