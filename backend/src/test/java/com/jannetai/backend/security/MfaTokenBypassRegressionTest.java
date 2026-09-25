package com.jannetai.backend.security;

import com.jannetai.backend.config.JwtProperties;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit GAP-001 regression tests: an MFA-pending token (issued by
 * POST /auth/login to ADMIN/SUPER_ADMIN before the OTP step) must never
 * authenticate a normal API request. Uses the REAL JwtService and the REAL
 * JwtAuthenticationFilter with real signed tokens - only the user lookup is
 * mocked - so the test exercises exactly the production code path.
 */
class MfaTokenBypassRegressionTest {

    private static final String TEST_SECRET = "this-is-a-test-secret-key-that-is-long-enough-for-hs256";

    private JwtService jwtService;
    private UserRepository userRepository;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(TEST_SECRET);
        properties.setAccessTokenExpiryMinutes(30);
        properties.setRefreshTokenExpiryDays(7);
        jwtService = new JwtService(properties);
        ReflectionTestUtils.setField(jwtService, "issuer", "jannet-ai-test");
        ReflectionTestUtils.invokeMethod(jwtService, "init");
        userRepository = mock(UserRepository.class);
        filter = new JwtAuthenticationFilter(jwtService, userRepository);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private User user(Role role) {
        return User.builder().userId(7L).role(role).status(UserStatus.ACTIVE)
                .fullName("Test " + role).mobileNumber("9000000099").build();
    }

    private Authentication runFilterWith(String bearerToken) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        request.addHeader("Authorization", "Bearer " + bearerToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response); // the chain always continues; SecurityConfig then returns 401
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void mfaPendingTokenForAdminDoesNotAuthenticate() throws Exception {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(Role.ADMIN)));
        String mfaToken = jwtService.generateMfaToken(user(Role.ADMIN));

        assertThat(runFilterWith(mfaToken)).isNull();
    }

    @Test
    void mfaPendingTokenForSuperAdminDoesNotAuthenticate() throws Exception {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(Role.SUPER_ADMIN)));
        String mfaToken = jwtService.generateMfaToken(user(Role.SUPER_ADMIN));

        assertThat(runFilterWith(mfaToken)).isNull();
    }

    @Test
    void accessTokenIssuedAfterMfaStillAuthenticatesWithTheUsersRole() throws Exception {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(Role.ADMIN)));
        String accessToken = jwtService.generateAccessToken(user(Role.ADMIN));

        Authentication auth = runFilterWith(accessToken);

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
    }

    @Test
    void citizenAccessTokenStillAuthenticates() throws Exception {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(Role.CITIZEN)));

        assertThat(runFilterWith(jwtService.generateAccessToken(user(Role.CITIZEN)))).isNotNull();
    }

    @Test
    void parseAccessTokenRejectsMfaTokenButMfaEndpointStillAcceptsIt() {
        String mfaToken = jwtService.generateMfaToken(user(Role.ADMIN));

        assertThatThrownBy(() -> jwtService.parseAccessToken(mfaToken)).isInstanceOf(JwtException.class);
        // The MFA verification flow itself is unaffected.
        assertThat(jwtService.validateMfaTokenAndGetUserId(mfaToken)).isEqualTo(7L);
    }

    @Test
    void accessTokenCarriesAccessTokenType() {
        String accessToken = jwtService.generateAccessToken(user(Role.CITIZEN));

        assertThat(jwtService.parseAccessToken(accessToken).get(JwtService.CLAIM_TOKEN_TYPE, String.class))
                .isEqualTo(JwtService.ACCESS_TOKEN_TYPE);
    }

    @Test
    void legacyAccessTokenWithoutTokenTypeIsStillAccepted() {
        // Tokens issued before GAP-001 was fixed have role + uid and no purpose/token_type.
        String legacy = Jwts.builder()
                .subject("9000000099")
                .claim("uid", 7L)
                .claim("role", "CITIZEN")
                .issuer("jannet-ai-test")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(jwtService.parseAccessToken(legacy).get("uid", Long.class)).isEqualTo(7L);
    }

    @Test
    void signedTokenWithoutRoleClaimIsRejected() {
        String noRole = Jwts.builder()
                .subject("9000000099")
                .claim("uid", 7L)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwtService.parseAccessToken(noRole)).isInstanceOf(JwtException.class);
    }

    @Test
    void signedTokenWithNonAccessTokenTypeIsRejected() {
        String otherType = Jwts.builder()
                .subject("9000000099")
                .claim("uid", 7L)
                .claim("role", "ADMIN")
                .claim("token_type", "MFA_PENDING")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwtService.parseAccessToken(otherType)).isInstanceOf(JwtException.class);
    }
}
