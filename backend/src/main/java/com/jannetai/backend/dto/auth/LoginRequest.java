package com.jannetai.backend.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/v1/auth/login (SRS 18 table: "mobile_number/email, password").
 * identifier accepts either a mobile number or an email address.
 */
public record LoginRequest(
        @NotBlank
        String identifier,

        @NotBlank
        String password
) {
}
