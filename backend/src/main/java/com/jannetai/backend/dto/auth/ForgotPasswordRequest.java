package com.jannetai.backend.dto.auth;

import jakarta.validation.constraints.NotBlank;

/** POST /api/v1/auth/forgot-password - "Forgot Password" button (SRS Screen 15.1). */
public record ForgotPasswordRequest(
        @NotBlank
        String mobileNumber
) {
}
