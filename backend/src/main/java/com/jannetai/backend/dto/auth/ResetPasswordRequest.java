package com.jannetai.backend.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** POST /api/v1/auth/reset-password (business rule: "password reset via OTP"). */
public record ResetPasswordRequest(
        @NotBlank
        String mobileNumber,

        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "must be a 6-digit code")
        String otpCode,

        @NotBlank
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^a-zA-Z0-9]).{8,}$",
                message = "must be at least 8 characters and include upper case, lower case, a digit, and a special character"
        )
        String newPassword
) {
}
