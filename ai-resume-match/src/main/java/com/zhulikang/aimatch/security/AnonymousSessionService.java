package com.zhulikang.aimatch.security;

import com.zhulikang.aimatch.config.ApiProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

@Component
public class AnonymousSessionService {
    public static final String COOKIE_NAME = "AI_MATCH_SESSION";
    private static final int SESSION_ID_BYTES = 32;
    private static final int SIGNATURE_BYTES = 32;

    private final byte[] signingKey;
    private final Duration sessionTtl;
    private final boolean secureCookie;
    private final Clock clock;
    private final SecureRandom secureRandom;

    @Autowired
    public AnonymousSessionService(ApiProperties properties) {
        this(
            properties.sessionSigningKey(),
            properties.sessionTtl(),
            properties.sessionCookieSecure(),
            Clock.systemUTC(),
            new SecureRandom()
        );
    }

    AnonymousSessionService(
        String signingKey,
        Duration sessionTtl,
        boolean secureCookie,
        Clock clock,
        SecureRandom secureRandom
    ) {
        if (signingKey == null || signingKey.length() < 32) {
            throw new IllegalArgumentException("api.session-signing-key must contain at least 32 characters");
        }
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalArgumentException("api.session-ttl must be positive");
        }
        this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
        this.sessionTtl = sessionTtl;
        this.secureCookie = secureCookie;
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    public ResolvedSession resolve(HttpServletRequest request) {
        String cookieValue = findCookie(request);
        ParsedSession parsed = parse(cookieValue);
        if (parsed != null) {
            return new ResolvedSession(OwnerId.sha256(parsed.sessionId()), null);
        }
        return createSession();
    }

    private ResolvedSession createSession() {
        byte[] idBytes = new byte[SESSION_ID_BYTES];
        secureRandom.nextBytes(idBytes);
        String sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes);
        Instant expiresAt = clock.instant().plus(sessionTtl);
        String unsigned = sessionId + "." + expiresAt.getEpochSecond();
        String token = unsigned + "." + sign(unsigned);
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token)
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Strict")
            .path("/")
            .maxAge(sessionTtl)
            .build();
        return new ResolvedSession(OwnerId.sha256(sessionId), cookie.toString());
    }

    private ParsedSession parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            byte[] idBytes = Base64.getUrlDecoder().decode(parts[0]);
            byte[] decodedSignature = Base64.getUrlDecoder().decode(parts[2]);
            long expiresAt = Long.parseLong(parts[1]);
            String unsigned = parts[0] + "." + parts[1];
            byte[] expectedSignature = sign(unsigned).getBytes(StandardCharsets.US_ASCII);
            byte[] suppliedSignature = parts[2].getBytes(StandardCharsets.US_ASCII);
            if (idBytes.length != SESSION_ID_BYTES
                || decodedSignature.length != SIGNATURE_BYTES
                || !MessageDigest.isEqual(expectedSignature, suppliedSignature)
                || expiresAt <= clock.instant().getEpochSecond()) {
                return null;
            }
            return new ParsedSession(parts[0]);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private String findCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
            .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
            .map(Cookie::getValue)
            .findFirst()
            .orElse(null);
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not sign anonymous session", ex);
        }
    }

    public record ResolvedSession(String ownerId, String setCookieHeader) {
        public boolean isNew() {
            return setCookieHeader != null;
        }
    }

    private record ParsedSession(String sessionId) {
    }
}
