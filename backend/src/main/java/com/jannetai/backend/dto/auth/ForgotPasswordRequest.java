package com.jannetai.backend.dto.auth;

import com.jannetai.backend.entity.enums.OtpChannel;
import jakarta.validation.constraints.NotBlank;

/** POST /api/v1/auth/forgot-password - "Forgot Password" button (SRS Screen 15.1). */
public record ForgotPasswordRequest(
        @NotBlank
        String mobileNumber,

        /** Audit GAP-004: SMS (default) or EMAIL (to the account's e-mail address). */
        OtpChannel channel
) {
    public ForgotPasswordRequest(String mobileNumber) {
        this(mobileNumber, null);
    }
}
