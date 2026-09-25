package com.jannetai.backend.dto.auth;

import com.jannetai.backend.entity.enums.OtpChannel;
import com.jannetai.backend.entity.enums.OtpPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** POST /api/v1/auth/resend-otp - "Resend OTP" link (SRS Screen 15.1). */
public record ResendOtpRequest(
        @NotBlank
        String mobileNumber,

        @NotNull
        OtpPurpose purpose,

        /** Audit GAP-004: SMS (default) or EMAIL. Ignored for LOGIN_MFA, which is always SMS. */
        OtpChannel channel
) {
    public ResendOtpRequest(String mobileNumber, OtpPurpose purpose) {
        this(mobileNumber, purpose, null);
    }
}
