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
}
