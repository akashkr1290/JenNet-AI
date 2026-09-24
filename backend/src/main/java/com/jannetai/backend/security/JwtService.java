package com.jannetai.backend.security;

import com.jannetai.backend.config.JwtProperties;
import com.jannetai.backend.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Issues and validates access-token JWTs only. Refresh tokens are opaque
 * random strings persisted in refresh_tokens (V17) - not JWTs - because
 * SRS 27.5 requires server-side single-use rotation and family revocation,
 * which a self-contained JWT can't support without also being persisted
 * (at which point it isn't buying anything over an opaque token).
 */
@Service
public class JwtService {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_PURPOSE = "purpose";
    private static final String MFA_PURPOSE = "MFA_PENDING";
    private static final long MFA_TOKEN_VALIDITY_MINUTES = 5;

    private final JwtProperties jwtProperties;
    @Value("${app.name:jannet-ai}")
    private String issuer;

    private SecretKey signingKey;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @PostConstruct
    void init() {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret (JWT_SECRET) must be set and at least 256 bits (32 bytes); " +
                    "see .env.example. Refusing to start with a weak/missing signing key.");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jwtProperties.getAccessTokenExpiryMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(user.getMobileNumber())
                .claim(CLAIM_USER_ID, user.getUserId())
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Short-lived (5 min) token identifying a user who passed
     * password-check but still owes an MFA OTP (SRS 27.1, Admin/Super
     * Admin only). Deliberately NOT usable as an access token: it carries
     * no role claim consumed by JwtAuthenticationFilter, and
     * AuthController only ever passes it to verifyMfaToken.
     */
    public String generateMfaToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getMobileNumber())
                .claim(CLAIM_USER_ID, user.getUserId())
                .claim(CLAIM_PURPOSE, MFA_PURPOSE)
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(MFA_TOKEN_VALIDITY_MINUTES, ChronoUnit.MINUTES)))
                .signWith(signingKey)
                .compact();
    }

    /** Returns the userId if this is a valid, unexpired MFA-pending token; throws JwtException otherwise. */
    public Long validateMfaTokenAndGetUserId(String mfaToken) {
        Claims claims = parseAndValidate(mfaToken);
        if (!MFA_PURPOSE.equals(claims.get(CLAIM_PURPOSE, String.class))) {
            throw new JwtException("Not an MFA token");
        }
        return claims.get(CLAIM_USER_ID, Long.class);
    }

    /** Returns parsed claims, or throws JwtException (invalid/expired/tampered). */
    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            parseAndValidate(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    public Long extractUserId(String token) {
        return parseAndValidate(token).get(CLAIM_USER_ID, Long.class);
    }

    public long accessTokenExpiryMinutes() {
        return jwtProperties.getAccessTokenExpiryMinutes();
    }
}
