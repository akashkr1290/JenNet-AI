package com.jannetai.backend.service.notification;

import com.jannetai.backend.service.OtpDeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Phase 15 replacement for the Phase 4 {@code LoggingOtpDeliveryService}
 * placeholder (SRS 15.6, Notification Module) - the seam
 * {@code OtpDeliveryService} describes: "Phase 15 only has to add a real
 * implementation ... and remove LoggingOtpDeliveryService, with no change
 * needed in the Authentication Module itself." AuthService/OtpService
 * still depend only on the {@link OtpDeliveryService} interface, so this
 * swap is invisible to them.
 *
 * OTP is delivered by SMS ({@link SmsGatewayClient}) - the SRS lists SMS
 * as the OTP channel (15.6: "6-digit OTP sent via SMS"), not email/push,
 * so this does not attempt those channels. When
 * {@code app.notification.sms.enabled} is false (this workspace's
 * default - no real SMS credentials), {@link SmsGatewayClient} itself
 * falls back to a logged stub, preserving local dev/demo usability
 * exactly like the class this replaces, but that fallback now lives in
 * one place (the gateway) shared with the rest of the Notification
 * Module, rather than being duplicated OTP-specific stub logic.
 *
 * No retry here: OTPs are short-lived and the citizen can request a
 * resend (existing AuthService flow) faster than a 3-attempt backoff
 * would take, so unlike {@link NotificationService}'s dispatch path this
 * does not retry - a single best-effort attempt, matching the previous
 * stub's single log call.
 */
@Service
@RequiredArgsConstructor
public class NotificationOtpDeliveryService implements OtpDeliveryService {

    private final SmsGatewayClient smsGatewayClient;

    /**
     * Matches {@code OtpService.VALIDITY_MINUTES} (5 minutes). Duplicated
     * as a literal rather than importing OtpService's private constant
     * (kept package-private-only by design there) - if that value ever
     * changes, this message and PHASE_HANDOFF.md's cross-reference note
     * must be updated together (documented in PROJECT_INTEGRATION.md
     * Section 6).
     */
    private static final long OTP_VALIDITY_MINUTES = 5;

    @Override
    public void sendOtp(String mobileNumber, String otpCode, String purposeLabel) {
        String message = "Your JanNet AI OTP for " + purposeLabel + " is " + otpCode
                + ". It expires in " + OTP_VALIDITY_MINUTES + " minutes. Do not share this code with anyone.";
        smsGatewayClient.send(mobileNumber, message);
    }
}
