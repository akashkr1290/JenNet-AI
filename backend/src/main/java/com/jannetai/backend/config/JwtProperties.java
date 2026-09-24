package com.jannetai.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound to app.jwt.* in application.yml, which in turn reads
 * JWT_SECRET / JWT_ACCESS_TOKEN_EXPIRY_MINUTES / JWT_REFRESH_TOKEN_EXPIRY_DAYS
 * from the environment (.env.example, Phase 1). See that file's Phase 4
 * changelog note: JWT_ACCESS_TOKEN_EXPIRY_MINUTES default was corrected
 * from 15 to 30 to match SRS 15.2/27.1 ("JWT access tokens expire after 30
 * minutes").
 */
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /** Must be >= 256 bits (32 bytes) for HS256 - enforced at startup by JwtService. */
    private String secret;
    private long accessTokenExpiryMinutes = 30;
    private int refreshTokenExpiryDays = 7;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAccessTokenExpiryMinutes() {
        return accessTokenExpiryMinutes;
    }

    public void setAccessTokenExpiryMinutes(long accessTokenExpiryMinutes) {
        this.accessTokenExpiryMinutes = accessTokenExpiryMinutes;
    }

    public int getRefreshTokenExpiryDays() {
        return refreshTokenExpiryDays;
    }

    public void setRefreshTokenExpiryDays(int refreshTokenExpiryDays) {
        this.refreshTokenExpiryDays = refreshTokenExpiryDays;
    }
}
