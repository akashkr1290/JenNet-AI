package com.jannetai.backend.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** POST /api/v1/auth/verify-otp (SRS 18 table). */
public record VerifyOtpRequest(
        @NotBlank
        String mobileNumber,

        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "must be a 6-digit code")
        String otpCode
) {
}
