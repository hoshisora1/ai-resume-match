package com.zhulikang.aimatch.application.analysis;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

final class AnalysisSubmissionRequestFingerprint {
    private static final int BUFFER_SIZE = 8192;

    private AnalysisSubmissionRequestFingerprint() {
    }

    static String create(MultipartFile file, String jobTitle, String jobContent) {
        MessageDigest digest = AnalysisSubmissionIdempotencyKey.sha256();
        addString(digest, "analysis-submission-request-v1");
        addString(digest, file.getOriginalFilename());
        addString(digest, file.getContentType());
        addString(digest, jobTitle);
        addString(digest, jobContent);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(file.getSize()).array());
        try (InputStream input = file.getInputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read uploaded resume", ex);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void addString(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
