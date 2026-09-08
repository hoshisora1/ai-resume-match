package com.zhulikang.aimatch.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AnonymousSessionServiceTest {
    private static final String SIGNING_KEY = "test-signing-key-with-at-least-32-characters";
    private static final Instant NOW = Instant.parse("2026-08-13T08:00:00Z");

    @Test
    void issuesAndReusesSignedHttpOnlySessionWithoutPersistingBearerValue() {
        AnonymousSessionService service = serviceAt(NOW);
        AnonymousSessionService.ResolvedSession issued = service.resolve(new MockHttpServletRequest());

        assertThat(issued.ownerId()).matches("[0-9a-f]{64}");
        assertThat(issued.setCookieHeader())
            .contains("AI_MATCH_SESSION=")
            .contains("HttpOnly")
            .contains("SameSite=Strict")
            .contains("Path=/");
        assertThat(issued.setCookieHeader()).doesNotContain(issued.ownerId());

        MockHttpServletRequest nextRequest = requestWithCookie(cookieValue(issued.setCookieHeader()));
        AnonymousSessionService.ResolvedSession reused = service.resolve(nextRequest);

        assertThat(reused.ownerId()).isEqualTo(issued.ownerId());
        assertThat(reused.isNew()).isFalse();
    }

    @Test
    void replacesTamperedOrExpiredSession() {
        AnonymousSessionService service = serviceAt(NOW);
        AnonymousSessionService.ResolvedSession issued = service.resolve(new MockHttpServletRequest());
        String cookie = cookieValue(issued.setCookieHeader());

        AnonymousSessionService.ResolvedSession tampered = service.resolve(
            requestWithCookie(cookie.substring(0, cookie.length() - 1) + "x")
        );
        assertThat(tampered.isNew()).isTrue();
        assertThat(tampered.ownerId()).isNotEqualTo(issued.ownerId());

        AnonymousSessionService expiredService = serviceAt(NOW.plus(Duration.ofMinutes(11)));
        AnonymousSessionService.ResolvedSession expired = expiredService.resolve(requestWithCookie(cookie));
        assertThat(expired.isNew()).isTrue();
        assertThat(expired.ownerId()).isNotEqualTo(issued.ownerId());
    }

    private AnonymousSessionService serviceAt(Instant instant) {
        return new AnonymousSessionService(
            SIGNING_KEY,
            Duration.ofMinutes(10),
            true,
            Clock.fixed(instant, ZoneOffset.UTC),
            new SecureRandom()
        );
    }

    private MockHttpServletRequest requestWithCookie(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AnonymousSessionService.COOKIE_NAME, value));
        return request;
    }

    private String cookieValue(String setCookieHeader) {
        String nameValue = setCookieHeader.substring(0, setCookieHeader.indexOf(';'));
        return nameValue.substring(nameValue.indexOf('=') + 1);
    }
}
