package com.jannetai.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jannetai.backend.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SRS 27.3 / 20.6: "Citizen-facing endpoints are rate-limited to 100
 * requests/minute per authenticated user" (429 on breach).
 *
 * ARCHITECTURE.md Section 7 explicitly excludes Redis for this academic-
 * scale project, so this is a per-JVM in-memory fixed-window counter, not a
 * distributed limiter - documented limitation: correct only for a single
 * backend instance. Only applied to authenticated requests, keyed by user
 * ID; unauthenticated requests to public auth endpoints are not covered
 * here (those get their own protection - e.g. OTP throttling, account
 * lockout - inside AuthService).
 */
@Component
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final int LIMIT_PER_WINDOW = 100;
    private static final long WINDOW_SECONDS = 60;

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();

    private record Window(long windowStartEpochSecond, AtomicInteger count) {
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserPrincipal principal) {
            Long userId = principal.getUser().getUserId();
            long now = Instant.now().getEpochSecond();
            Window window = windows.compute(userId, (id, existing) -> {
                if (existing == null || now - existing.windowStartEpochSecond() >= WINDOW_SECONDS) {
                    return new Window(now, new AtomicInteger(1));
                }
                existing.count().incrementAndGet();
                return existing;
            });
            if (window.count().get() > LIMIT_PER_WINDOW) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                ErrorResponse body = ErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS.value(), "TOO_MANY_REQUESTS",
                        "Rate limit exceeded (100 requests/minute)", request.getRequestURI());
                objectMapper.writeValue(response.getWriter(), body);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
