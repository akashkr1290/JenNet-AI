package com.jannetai.backend.security;

import com.jannetai.backend.entity.User;
import com.jannetai.backend.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Stateless bearer-token authentication (SRS 27.1). On a missing/invalid
 * token this filter simply leaves the SecurityContext empty and continues
 * the chain - SecurityConfig's authorizeHttpRequests rules (backed by
 * Spring Security's own entry point) are what turn "no authentication" into
 * a 401/403, keeping this filter free of response-writing logic.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX) && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(PREFIX.length());
            try {
                // Audit GAP-001: parseAccessToken (not parseAndValidate) so an
                // MFA-pending token can never authenticate an API request.
                Claims claims = jwtService.parseAccessToken(token);
                Long userId = claims.get("uid", Long.class);
                Optional<User> user = userRepository.findById(userId);
                if (user.isPresent()) {
                    UserPrincipal principal = new UserPrincipal(user.get());
                    // isEnabled/isAccountNonLocked reflect live status/lock state on
                    // every request, not just at token-issue time (SRS: suspended
                    // accounts and locked-out accounts must be rejected immediately).
                    if (principal.isEnabled() && principal.isAccountNonLocked()) {
                        var authToken = new UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities());
                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                    }
                }
            } catch (JwtException | IllegalArgumentException ex) {
                // Invalid/expired/tampered token: leave unauthenticated, let
                // SecurityConfig's access rules produce the 401/403.
            }
        }
        filterChain.doFilter(request, response);
    }
}
