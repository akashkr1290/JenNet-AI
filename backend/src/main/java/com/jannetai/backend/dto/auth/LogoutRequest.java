package com.jannetai.backend.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/v1/auth/logout. Not in the SRS's "representative" endpoint
 * table but required to make refresh-token revocation reachable at all;
 * revokes only this token's family (this device/session), not every
 * session - see AuthController for the "revoke everywhere" variant.
 */
public record LogoutRequest(
        @NotBlank
        String refreshToken
) {
}
