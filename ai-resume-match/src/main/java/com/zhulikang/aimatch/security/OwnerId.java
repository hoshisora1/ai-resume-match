package com.zhulikang.aimatch.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class OwnerId {
    public static final String LEGACY = "0".repeat(64);

    private OwnerId() {
    }

    public static String requireValid(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("ownerId must be a lowercase SHA-256 value");
        }
        return value;
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static String scopeHash(String ownerId, String valueHash) {
        return sha256(requireValid(ownerId) + ":" + valueHash);
    }
}
