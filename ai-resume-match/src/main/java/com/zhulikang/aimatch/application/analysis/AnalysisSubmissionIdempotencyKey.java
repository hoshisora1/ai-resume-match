package com.zhulikang.aimatch.application.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class AnalysisSubmissionIdempotencyKey {
    public static final int MAX_LENGTH = 128;
    public static final String SAFE_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";

    private AnalysisSubmissionIdempotencyKey() {
    }

    public static String hash(String key) {
        validate(key);
        MessageDigest digest = sha256();
        digest.update("analysis-submission-idempotency-key-v1\0".getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
    }

    public static void validate(String key) {
        if (key == null || key.length() > MAX_LENGTH || !key.matches(SAFE_PATTERN)) {
            throw new IllegalArgumentException(
                "Idempotency-Key must contain 1-128 ASCII letters, digits, dots, underscores, colons, or hyphens"
            );
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
