package com.jannetai.backend.service;

/**
 * SMS/email OTP delivery is owned by the Notification Module (SRS 15.6).
 * This interface is the integration seam AuthService/OtpService code
 * against - Phase 15 supplied the real implementation
 * ({@code service.notification.NotificationOtpDeliveryService}, backed by
 * {@code SmsGatewayClient}), replacing the Phase 4
 * {@code LoggingOtpDeliveryService} placeholder, with no change needed in
 * the Authentication Module itself.
 */
public interface OtpDeliveryService {
    void sendOtp(String mobileNumber, String otpCode, String purposeLabel);

    /**
     * Audit GAP-004 (SRS 15.2 "OTP-based mobile/email verification"): the same
     * code delivered by e-mail. Throws {@code OtpDeliveryException} when it
     * cannot be sent, exactly like {@link #sendOtp}.
     */
    void sendOtpByEmail(String emailAddress, String otpCode, String purposeLabel);

    /** Audit GAP-004: whether {@link #sendOtpByEmail} can currently deliver (e-mail enabled). */
    boolean isEmailOtpAvailable();
}
