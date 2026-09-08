package com.zhulikang.aimatch.application.analysis;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class AnalysisInputFingerprint {
    static final String RUN_METADATA_SCHEMA_VERSION = "agent-run-v1";
    static final String REQUEST_SCHEMA_VERSION = "agent-analysis-request-v1";
    static final String AGENT_RUNTIME_VERSION = "bounded-tool-agent-v1";
    static final String FINGERPRINT_VERSION = "sha256-task-scoped-length-prefixed-v1";

    private AnalysisInputFingerprint() {
    }

    static String create(AnalysisInput input) {
        MessageDigest digest = sha256();
        update(digest, FINGERPRINT_VERSION);
        update(digest, input.taskId().toString());
        update(digest, input.resumeText());
        update(digest, input.jobTitle());
        update(digest, input.jobDescription());
        update(digest, Integer.toString(input.skillTags().size()));
        input.skillTags().forEach(value -> update(digest, value));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(encoded.length).array());
        digest.update(encoded);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
