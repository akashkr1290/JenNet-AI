package com.jannetai.backend.entity.enums;

/**
 * Matches otp_verifications.purpose's CHECK constraint
 * (V16__create_otp_verifications.sql) exactly - do not add/rename values
 * here without a corresponding migration.
 */
public enum OtpPurpose {
    REGISTRATION,
    LOGIN_MFA,
    PASSWORD_RESET
}
