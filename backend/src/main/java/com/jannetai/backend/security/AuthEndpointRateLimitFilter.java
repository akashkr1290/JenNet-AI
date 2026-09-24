package com.jannetai.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jannetai.backend.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gap-backlog Patch 50 (Sep 2026 strict recheck). The pre-existing
 * {@link RateLimitingFilter} only throttles AUTHENTICATED callers (it keys on
 * the JWT principal's userId and does nothing when there is no principal) -
 * so /register, /login, /send-otp, /resend-otp and /forgot-password, the exact
 * endpoints Patch 50 names and all unauthenticated by design, were never
 * rate-limited at all. An earlier audit pass wrongly listed Patch 50 as
 * already complete; this filter closes that real gap.
 *
 * <p>Keyed on client IP + request path (so a burst against /send-otp does not
 * also lock the same IP out of /login). Client IP comes from
 * {@code app.rate-limit.client-ip-header} when configured - production sets it
 * to {@code X-Real-IP}, which deployment/nginx/jannet.conf overwrites with
 * nginx's own {@code $remote_addr} (so a client cannot spoof it) - and falls
 * back to the socket address otherwise. Never trust that header on a
 * deployment where the backend is reachable without going through nginx.
 *
 * <p>Trade-off, documented not hidden: many Indian mobile users share one
 * public IP behind carrier-grade NAT, so the limit is configurable and
 * deliberately not tiny. Per-account protections (login lockout, OTP attempt
 * limits from Phase 4) still apply on top of this per-IP limit.
 *
 * <p>In-memory, per-instance state (same scope as RateLimitingFilter) - a
 * multi-instance deployment would need a shared store (Redis, Patch 30) for a
 * global limit.
 */
@Component
public class AuthEndpointRateLimitFilter extends OncePerRequestFilter {

    private static final String AUTH_PATH_PREFIX = "/api/v1/auth/";
    private static final long WINDOW_SECONDS = 60;
    private static final int MAX_TRACKED_KEYS = 50_000;

    private final ObjectMapper objectMapper;
    private final String clientIpHeader;
    private final int limitPerWindow;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private record Window(long windowStartEpochSecond, AtomicInteger count) {
    }

    public AuthEndpointRateLimitFilter(ObjectMapper objectMapper,
                                       @Value("${app.rate-limit.client-ip-header:}") String clientIpHeader,
                                       @Value("${app.rate-limit.auth-requests-per-minute:20}") int limitPerWindow) {
        this.objectMapper = objectMapper;
        this.clientIpHeader = clientIpHeader;
        this.limitPerWindow = limitPerWindow;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getRequestURI().startsWith(AUTH_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        long now = Instant.now().getEpochSecond();
        if (windows.size() > MAX_TRACKED_KEYS) {
            windows.entrySet().removeIf(e -> now - e.getValue().windowStartEpochSecond() >= WINDOW_SECONDS);
        }
        String key = clientIp(request) + "|" + request.getRequestURI();
        Window window = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStartEpochSecond() >= WINDOW_SECONDS) {
                return new Window(now, new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });
        if (window.count().get() > limitPerWindow) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(WINDOW_SECONDS));
            ErrorResponse body = ErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS.value(), "TOO_MANY_REQUESTS",
                    "Too many attempts from this network - please wait a minute and try again",
                    request.getRequestURI());
            objectMapper.writeValue(response.getWriter(), body);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        if (clientIpHeader != null && !clientIpHeader.isBlank()) {
            String value = request.getHeader(clientIpHeader);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
