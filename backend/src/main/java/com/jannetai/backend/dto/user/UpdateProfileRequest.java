package com.jannetai.backend.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PUT /api/v1/users/me (Phase 5, Citizen Module - SRS 15.1 "profile
 * management"). Deliberately does NOT include mobileNumber or email:
 * both are login identifiers with their own verification state
 * (mobile_verified_at / email_verified_at, Phase 4), and changing either
 * self-service would require a dedicated re-verification (OTP) flow that
 * is out of scope for this phase - see PROJECT_INTEGRATION.md Section 6
 * for the written decision. Role, ward-assignment-by-staff, department,
 * and status are never self-service (Admin-only, Phase 14).
 */
public record UpdateProfileRequest(

        @NotBlank @Size(max = 100)
        String fullName,

        /** Null clears the ward assignment; a non-null value must reference an active ward. */
        Long wardId
) {
}
