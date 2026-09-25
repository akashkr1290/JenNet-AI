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
    /**
     * Audit GAP-001: every token now states what it is for. Access tokens
     * carry token_type=ACCESS; MFA-pending tokens carry token_type=MFA_PENDING
     * (plus the legacy purpose claim). Only {@link #parseAccessToken} may be
     * used to authenticate API requests.
     */
    static final String CLAIM_TOKEN_TYPE = "token_type";
    static final String ACCESS_TOKEN_TYPE = "ACCESS";
    private static final long MFA_TOKEN_VALIDITY_MINUTES = 5;

    private final JwtProperties jwtProperties;
    @Value("${app.name:jannet-ai}")
    private String issuer;

    private SecretKey signingKey;

    /**
     * Audit GAP-019: application.yml ships a public placeholder default for
     * JWT_SECRET so local runs start without configuration. Production
     * (application-prod.yml sets this to false) refuses to start with it.
     */
    @Value("${app.jwt.allow-placeholder-secret:true}")
    private boolean allowPlaceholderSecret = true;

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
        if (!allowPlaceholderSecret && secret.toUpperCase().contains("CHANGE_ME")) {
            throw new IllegalStateException(
                    "app.jwt.secret (JWT_SECRET) is still the public placeholder value; set a real, "
                    + "randomly generated secret (e.g. `openssl rand -hex 32`) before starting in production.");
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
                .claim(CLAIM_TOKEN_TYPE, ACCESS_TOKEN_TYPE)
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
                .claim(CLAIM_TOKEN_TYPE, MFA_PURPOSE)
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

    /**
     * Audit GAP-001: the ONLY method allowed to turn a bearer token into an
     * authenticated API request. Rejects (JwtException) any token that is not
     * a normal access token:
     * <ul>
     *   <li>an MFA-pending token (purpose claim present, or token_type other than ACCESS),</li>
     *   <li>a token without the role claim every access token carries.</li>
     * </ul>
     * Access tokens issued before this change have no token_type claim but do
     * have a role claim and no purpose claim, so they stay valid until expiry.
     */
    public Claims parseAccessToken(String token) {
        Claims claims = parseAndValidate(token);
        if (claims.get(CLAIM_PURPOSE, String.class) != null) {
            throw new JwtException("MFA-pending token cannot be used as an access token");
        }
        String tokenType = claims.get(CLAIM_TOKEN_TYPE, String.class);
        if (tokenType != null && !ACCESS_TOKEN_TYPE.equals(tokenType)) {
            throw new JwtException("Token type " + tokenType + " cannot be used as an access token");
        }
        if (claims.get(CLAIM_ROLE, String.class) == null) {
            throw new JwtException("Token has no role claim and cannot be used as an access token");
        }
        return claims;
    }

    /** Returns parsed claims, or throws JwtException (invalid/expired/tampered). Does NOT check token purpose - never use it to authenticate a request; use {@link #parseAccessToken}. */
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
