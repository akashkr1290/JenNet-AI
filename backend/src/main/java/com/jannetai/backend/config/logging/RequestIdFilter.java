package com.jannetai.backend.config.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Audit GAP-041: gives every HTTP request a correlation id before anything
 * else runs (highest precedence, ahead of the security filter chain), so every
 * log line of the request - including GlobalExceptionHandler's
 * UNHANDLED_EXCEPTION line - carries it. The id comes from nginx
 * ({@code proxy_set_header X-Request-Id $request_id}) when present and valid,
 * and is echoed in the response header for support requests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String requestId = RequestIds.adoptOrCreate(request.getHeader(RequestIds.HEADER));
        MDC.put(RequestIds.MDC_KEY, requestId);
        response.setHeader(RequestIds.HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestIds.MDC_KEY);
        }
    }
}
