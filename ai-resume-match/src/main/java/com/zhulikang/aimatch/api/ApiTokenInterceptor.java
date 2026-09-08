package com.zhulikang.aimatch.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhulikang.aimatch.config.ApiProperties;
import com.zhulikang.aimatch.security.AnonymousSessionService;
import com.zhulikang.aimatch.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ApiTokenInterceptor implements HandlerInterceptor {
    private static final String HEADER_NAME = "X-API-Token";

    private final boolean apiTokenConfigured;
    private final byte[] apiTokenBytes;
    private final ObjectMapper objectMapper;
    private final AnonymousSessionService anonymousSessionService;

    public ApiTokenInterceptor(
        ApiProperties properties,
        ObjectMapper objectMapper,
        AnonymousSessionService anonymousSessionService
    ) {
        String apiToken = properties.token();
        this.apiTokenConfigured = apiToken != null && !apiToken.isBlank();
        this.apiTokenBytes = this.apiTokenConfigured ? apiToken.getBytes(StandardCharsets.UTF_8) : new byte[0];
        this.objectMapper = objectMapper;
        this.anonymousSessionService = anonymousSessionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!isAuthorized(request.getHeader(HEADER_NAME))) {
            writeUnauthorized(request, response);
            return false;
        }
        AnonymousSessionService.ResolvedSession session = anonymousSessionService.resolve(request);
        RequestIdentity.set(session.ownerId());
        if (session.isNew()) {
            response.addHeader("Set-Cookie", session.setCookieHeader());
        }
        return true;
    }

    @Override
    public void afterCompletion(
        HttpServletRequest request,
        HttpServletResponse response,
        Object handler,
        Exception ex
    ) {
        RequestIdentity.clear();
    }

    private boolean isAuthorized(String requestToken) {
        return apiTokenConfigured
            && requestToken != null
            && MessageDigest.isEqual(apiTokenBytes, requestToken.getBytes(StandardCharsets.UTF_8));
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
            response.getWriter(),
            ApiProblemDetail.of(
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "Unauthorized",
                request.getRequestURI()
            )
        );
    }
}
