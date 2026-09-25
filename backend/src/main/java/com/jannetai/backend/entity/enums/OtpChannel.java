package com.jannetai.backend.entity.enums;

/**
 * Audit GAP-004: where an OTP is delivered. Matches
 * otp_verifications.channel's CHECK constraint (V25__add_otp_channel.sql).
 */
public enum OtpChannel {
    SMS,
    EMAIL
}
