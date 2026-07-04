package com.zhulikang.aimatch.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
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

    public ApiTokenInterceptor(@Value("${api.token}") String apiToken, ObjectMapper objectMapper) {
        this.apiTokenConfigured = apiToken != null && !apiToken.isBlank();
        this.apiTokenBytes = this.apiTokenConfigured ? apiToken.getBytes(StandardCharsets.UTF_8) : new byte[0];
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!isAuthorized(request.getHeader(HEADER_NAME))) {
            writeUnauthorized(response);
            return false;
        }
        return true;
    }

    private boolean isAuthorized(String requestToken) {
        return apiTokenConfigured
            && requestToken != null
            && MessageDigest.isEqual(apiTokenBytes, requestToken.getBytes(StandardCharsets.UTF_8));
    }

    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), new ApiErrorResponse("UNAUTHORIZED", "Unauthorized"));
    }
}
