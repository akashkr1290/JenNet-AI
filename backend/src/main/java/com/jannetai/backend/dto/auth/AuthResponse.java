package com.jannetai.backend.dto.auth;

/** 200 OK body for /login (non-MFA), /refresh, and /mfa/verify. */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        UserProfileResponse user
) {
}
