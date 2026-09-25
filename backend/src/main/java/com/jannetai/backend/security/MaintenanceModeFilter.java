package com.jannetai.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jannetai.backend.dto.admin.PlatformStatusResponse;
import com.jannetai.backend.exception.ErrorResponse;
import com.jannetai.backend.service.admin.PlatformStatusService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Audit GAP-037 (SRS 15.11 maintenance-mode control; SRS 15.1 Exceptions
 * "submission during platform maintenance window is queued and auto-retried").
 *
 * While an Admin has maintenance mode on, every state-changing request
 * (POST/PUT/PATCH/DELETE) is answered {@code 503 MAINTENANCE} with a
 * {@code Retry-After} header, instead of running against a system that is
 * being maintained. Reads keep working. The Flutter app keeps a refused
 * complaint submission in its offline queue and retries it automatically.
 *
 * Never blocked: Admin/Super Admin requests (they must be able to switch it
 * off and work during the window), /api/v1/auth/** (so sessions can still be
 * started and refreshed), and actuator endpoints. Runs inside the security
 * chain after JWT authentication (SecurityConfig), so the caller's role is known.
 */
@Component
@RequiredArgsConstructor
public class MaintenanceModeFilter extends OncePerRequestFilter {

    static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    static final Set<String> EXEMPT_ROLES = Set.of("ROLE_ADMIN", "ROLE_SUPER_ADMIN");

    private final PlatformStatusService platformStatusService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (!WRITE_METHODS.contains(request.getMethod()) || isExemptPath(request.getRequestURI()) || isAdmin()) {
            filterChain.doFilter(request, response);
            return;
        }
        PlatformStatusResponse status = platformStatusService.current();
        if (!status.maintenanceMode()) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(status.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(HttpStatus.SERVICE_UNAVAILABLE.value(),
                "MAINTENANCE", status.maintenanceMessage(), request.getRequestURI()));
    }

    static boolean isExemptPath(String uri) {
        return uri == null || uri.startsWith("/api/v1/auth/") || uri.startsWith("/actuator/");
    }

    private static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (EXEMPT_ROLES.contains(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
