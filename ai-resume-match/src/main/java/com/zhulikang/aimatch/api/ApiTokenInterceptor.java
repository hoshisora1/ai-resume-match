package com.zhulikang.aimatch.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiTokenInterceptor implements HandlerInterceptor {
    private static final String HEADER_NAME = "X-API-Token";

    private final String apiToken;

    public ApiTokenInterceptor(@Value("${api.token}") String apiToken) {
        this.apiToken = apiToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (apiToken == null || apiToken.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        if (!apiToken.equals(request.getHeader(HEADER_NAME))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}
