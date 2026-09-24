package com.jannetai.backend.security;

import com.jannetai.backend.config.JwtProperties;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JwtService} - access-token issuance/validation and
 * the short-lived MFA-pending token (SRS 27.1). No Spring context: {@code
 * @PostConstruct init()} is invoked directly after constructing with a
 * real {@link JwtProperties} (matching this project's own startup-time
 * secret-length validation, which these tests also exercise).
 *
 * NOT EXECUTED in this workspace (no Maven Central reach for
 * io.jsonwebtoken/jjwt - see PROJECT_PROGRESS.md Phase 20 TESTS section).
 * Manually validated against JwtService.java's actual claim names
 * (CLAIM_ROLE="role", CLAIM_USER_ID="uid", CLAIM_PURPOSE="purpose") and
 * method signatures.
 */
class JwtServiceTest {

    // 32+ bytes, satisfies JwtService.init()'s HS256 minimum-length check.
    private static final String TEST_SECRET = "this-is-a-test-secret-key-that-is-long-enough-for-hs256";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(TEST_SECRET);
        properties.setAccessTokenExpiryMinutes(30);
        properties.setRefreshTokenExpiryDays(7);
        jwtService = new JwtService(properties);
        ReflectionTestUtils.setField(jwtService, "issuer", "jannet-ai-test");
        ReflectionTestUtils.invokeMethod(jwtService, "init");
    }

    private User sampleUser() {
        return User.builder()
                .userId(42L)
                .mobileNumber("+911234567890")
                .role(Role.CITIZEN)
                .fullName("Test Citizen")
                .build();
    }

    @Test
    void initRejectsASecretShorterThan32Bytes() {
        // ReflectionUtils.invokeMethod (which ReflectionTestUtils delegates
        // to) rethrows an unchecked target exception as-is, unwrapped - so
        // the IllegalStateException JwtService.init() throws propagates
        // directly here, not nested inside a reflection wrapper exception.
        JwtProperties weak = new JwtProperties();
        weak.setSecret("too-short");
        JwtService service = new JwtService(weak);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "init"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256 bits");
    }

    @Test
    void initRejectsANullSecret() {
        JwtProperties missing = new JwtProperties();
        JwtService service = new JwtService(missing);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "init"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void generatedAccessTokenIsValid() {
        String token = jwtService.generateAccessToken(sampleUser());
        assertThat(jwtService.isValid(token)).isTrue();
    }

    @Test
    void accessTokenCarriesUserIdAndRoleClaims() {
        String token = jwtService.generateAccessToken(sampleUser());
        var claims = jwtService.parseAndValidate(token);
        assertThat(claims.get("uid", Long.class)).isEqualTo(42L);
        assertThat(claims.get("role", String.class)).isEqualTo("CITIZEN");
        assertThat(claims.getSubject()).isEqualTo("+911234567890");
        assertThat(claims.getIssuer()).isEqualTo("jannet-ai-test");
    }

    @Test
    void extractUserIdReturnsTheCorrectId() {
        String token = jwtService.generateAccessToken(sampleUser());
        assertThat(jwtService.extractUserId(token)).isEqualTo(42L);
    }

    @Test
    void tamperedTokenIsInvalid() {
        String token = jwtService.generateAccessToken(sampleUser());
        // Flip a character in the signature segment to simulate tampering.
        String[] parts = token.split("\\.");
        String tamperedSignature = new StringBuilder(parts[2]).reverse().toString();
        String tampered = parts[0] + "." + parts[1] + "." + tamperedSignature;

        assertThat(jwtService.isValid(tampered)).isFalse();
        assertThatThrownBy(() -> jwtService.parseAndValidate(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageStringIsNotAValidToken() {
        assertThat(jwtService.isValid("not.a.jwt")).isFalse();
    }

    @Test
    void tokenSignedWithADifferentSecretIsRejected() {
        JwtProperties otherProperties = new JwtProperties();
        otherProperties.setSecret("a-completely-different-secret-also-long-enough-32b");
        JwtService otherService = new JwtService(otherProperties);
        ReflectionTestUtils.setField(otherService, "issuer", "jannet-ai-test");
        ReflectionTestUtils.invokeMethod(otherService, "init");

        String tokenFromOtherService = otherService.generateAccessToken(sampleUser());

        assertThat(jwtService.isValid(tokenFromOtherService)).isFalse();
    }

    // ---- MFA-pending token (SRS 27.1) ----

    @Test
    void mfaTokenValidatesAndReturnsUserId() {
        String mfaToken = jwtService.generateMfaToken(sampleUser());
        assertThat(jwtService.validateMfaTokenAndGetUserId(mfaToken)).isEqualTo(42L);
    }

    @Test
    void accessTokenCannotBeUsedAsAnMfaToken() {
        // No CLAIM_PURPOSE="MFA_PENDING" on a normal access token - must
        // be rejected, preventing an access token from being replayed
        // against the MFA-verification endpoint.
        String accessToken = jwtService.generateAccessToken(sampleUser());
        assertThatThrownBy(() -> jwtService.validateMfaTokenAndGetUserId(accessToken))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("Not an MFA token");
    }

    @Test
    void mfaTokenIsAlsoAValidGenericJwtStructurally() {
        // isValid only checks signature/expiry, not purpose - the purpose
        // gate lives specifically in validateMfaTokenAndGetUserId.
        String mfaToken = jwtService.generateMfaToken(sampleUser());
        assertThat(jwtService.isValid(mfaToken)).isTrue();
    }
}
