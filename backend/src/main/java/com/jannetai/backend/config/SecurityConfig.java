package com.jannetai.backend.config;

import com.jannetai.backend.security.JwtAuthenticationFilter;
import com.jannetai.backend.security.AuthEndpointRateLimitFilter;
import com.jannetai.backend.security.RateLimitingFilter;
import com.jannetai.backend.security.RestAccessDeniedHandler;
import com.jannetai.backend.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Security baseline required from Phase 4 onward (ARCHITECTURE.md Section
 * 6): stateless JWT auth, RBAC enforced server-side on every endpoint,
 * CORS policy, secure headers (Spring Security defaults: X-Content-Type-
 * Options, X-Frame-Options, HSTS-on-HTTPS, etc. - left at their secure
 * defaults, not disabled).
 *
 * Path-based rules below only cover what actually exists yet
 * (/api/v1/auth/**, /api/v1/users/me, Swagger, Actuator). Every future
 * business endpoint added by Phase 5+ must add its own
 * authorizeHttpRequests rule (or @PreAuthorize) here / on its controller -
 * "authenticated()" is NOT a safe-by-default fallback for admin-tier data;
 * the eventual .anyRequest() rule intentionally stays authenticated()-only
 * (not permitAll) so a forgotten rule fails closed, not open.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitingFilter rateLimitingFilter;
    private final AuthEndpointRateLimitFilter authEndpointRateLimitFilter; // Gap-backlog Patch 50
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Security Section 27.3: bcrypt for password hashing.
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless bearer-token API, no cookies/CSRF surface
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // Registration, OTP verification, login, refresh, forgot-password
                        // are the only endpoints usable without a token (SRS 18: "All
                        // endpoints except registration, login, and OTP verification
                        // require a valid JWT").
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Gap-backlog Patch 8 (Sep 2026 audit): the citizen registration
                        // screen needs a ward picker before any JWT exists. This is
                        // deliberately a separate path from the authenticated
                        // /api/v1/wards/** below (PublicWardController's Javadoc has the
                        // full decision record) rather than loosening that endpoint,
                        // which /users/me profile-update already relies on staying
                        // auth-gated. WardResponse is already documented public-safe
                        // (id/name/code only) so this adds no new data exposure.
                        .requestMatchers("/api/v1/public/wards/**").permitAll()
                        // Gap-backlog Patch 6 (Sep 2026 audit): image bytes behind a
                        // presigned, HMAC-signed, time-limited link - see
                        // ImageContentController's Javadoc for why "public" here does not
                        // mean "unauthorized": possessing the signed link IS the
                        // authorization, the same trust model a real S3 presigned URL uses.
                        .requestMatchers("/api/v1/images/content").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info").permitAll()
                        // Gap-backlog Patch 17 (Sep 2026 audit): metrics/prometheus expose
                        // operational data (call volumes, latencies) - ADMIN/SUPER_ADMIN only,
                        // unlike the unauthenticated health/info probes above.
                        .requestMatchers("/actuator/metrics/**", "/actuator/prometheus")
                            .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/v1/users/me").authenticated()
                        .requestMatchers("/api/v1/users/me/**").authenticated()
                        // Phase 5, Citizen Module: ward reference-data lookup. SRS 18 only
                        // names registration/login/OTP-verification as unauthenticated, so
                        // this stays authenticated()-only, not permitAll, same as /users/me.
                        .requestMatchers("/api/v1/wards/**").authenticated()
                        // Phase 6, Complaint Module: role-specific gating (citizen-only
                        // create/reopen, staff-only verify/status) is enforced by
                        // @PreAuthorize on ComplaintController itself; this rule only
                        // establishes "must be authenticated at all", same division of
                        // responsibility as /users/me above.
                        .requestMatchers("/api/v1/complaints/**").authenticated()
                        // Phase 13, Department Head Module: role-specific gating (own-
                        // department-only for DEPARTMENT_HEAD, unrestricted for ADMIN/
                        // SUPER_ADMIN) is enforced by @PreAuthorize + DepartmentPerformanceService
                        // on DepartmentController itself - same division of responsibility
                        // as /api/v1/complaints/** above.
                        .requestMatchers("/api/v1/departments/**").authenticated()
                        // Phase 14, Admin & Settings Module: role-specific gating (ADMIN/
                        // SUPER_ADMIN only, with the finer SUPER_ADMIN-only-for-ADMIN-
                        // accounts rule enforced inside AdminUserService) is enforced by
                        // @PreAuthorize on each admin controller - same division of
                        // responsibility as /api/v1/complaints/** and
                        // /api/v1/departments/** above. Covers AdminUserController,
                        // AdminSettingsController, AdminRoutingRuleController (this last
                        // one pre-existed from Phase 11 without its own explicit rule,
                        // relying on the anyRequest() fallback below - now made explicit).
                        .requestMatchers("/api/v1/admin/**").authenticated()
                        // Phase 15, Notification Module: no role restriction beyond
                        // authentication (every endpoint scopes to the caller's own
                        // notifications/preferences via principal.getUser(), never a
                        // path/query id) - same division of responsibility as /users/me.
                        .requestMatchers("/api/v1/notifications/**").authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitingFilter, JwtAuthenticationFilter.class)
                // Gap-backlog Patch 50: pre-auth endpoints are throttled per client IP,
                // before any JWT processing (they carry no token to key on).
                .addFilterBefore(authEndpointRateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /** Browser origins allowed to call the API (Flutter Web frontend); see application.yml app.cors. */
    @Value("${app.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}")
    private String corsAllowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        // The Flutter mobile app sends no Origin header and Swagger UI is
        // same-origin, so neither is affected by this list. The Flutter WEB
        // frontend runs in a browser on its own origin (Docker: port 3000), which
        // is exactly the "real web origin" this previously-wildcard setting was
        // waiting for - so origins are now an explicit, configurable allow-list
        // (app.cors.allowed-origins / CORS_ALLOWED_ORIGINS, comma-separated;
        // Spring origin patterns such as https://*.example.org are accepted).
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
