package com.jannetai.backend.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * POST /api/v1/auth/mfa/verify - second step of login for Admin/Super
 * Admin (SRS 27.1). mfaToken is the short-lived token returned by
 * /auth/login when mfa_required was true.
 */
public record MfaVerifyRequest(
        @NotBlank
        String mfaToken,

        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "must be a 6-digit code")
        String otpCode
) {
}
