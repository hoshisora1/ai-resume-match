package com.zhulikang.aimatch.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = RequestCorrelation.safeOrNew(request.getHeader(RequestCorrelation.REQUEST_ID_HEADER));
        String correlationId = RequestCorrelation.safeOrNew(request.getHeader(RequestCorrelation.CORRELATION_ID_HEADER));
        request.setAttribute(RequestCorrelation.REQUEST_ID_ATTRIBUTE, requestId);
        request.setAttribute(RequestCorrelation.CORRELATION_ID_ATTRIBUTE, correlationId);
        response.setHeader(RequestCorrelation.REQUEST_ID_HEADER, requestId);
        response.setHeader(RequestCorrelation.CORRELATION_ID_HEADER, correlationId);
        RequestCorrelation.put(requestId, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestCorrelation.clear();
        }
    }
}
