package com.jannetai.backend.dto.auth;

import jakarta.validation.constraints.NotBlank;

/** POST /api/v1/auth/refresh (SRS 18 table). */
public record RefreshRequest(
        @NotBlank
        String refreshToken
) {
}
