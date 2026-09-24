package com.jannetai.backend.dto.auth;

import com.jannetai.backend.entity.enums.OtpPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** POST /api/v1/auth/resend-otp - "Resend OTP" link (SRS Screen 15.1). */
public record ResendOtpRequest(
        @NotBlank
        String mobileNumber,

        @NotNull
        OtpPurpose purpose
) {
}
