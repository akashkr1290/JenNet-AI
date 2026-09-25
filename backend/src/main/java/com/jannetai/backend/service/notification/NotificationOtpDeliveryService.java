package com.jannetai.backend.service.notification;

import com.jannetai.backend.exception.OtpDeliveryException;
import com.jannetai.backend.service.OtpDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

/**
 * OTP delivery by SMS (SRS 15.6: "6-digit OTP sent via SMS") through
 * {@link SmsGatewayClient}. Phase 15 replacement for the Phase 4
 * {@code LoggingOtpDeliveryService}; AuthService/OtpService depend only on the
 * {@link OtpDeliveryService} interface.
 *
 * REGISTRATION OTP FIX. Previously this delegated straight to
 * {@code SmsGatewayClient.send}, which - when SMS is not configured - only logs
 * a stub line and returns normally. The OTP was committed, the API answered
 * 201 Created / "OTP resent", and the app opened the OTP screen for a code that
 * was never sent to anyone. Delivery is now explicit:
 * <ul>
 *   <li>SMS configured: send; a provider failure throws
 *       {@link OtpDeliveryException} (HTTP 503 OTP_DELIVERY_FAILED) instead of
 *       surfacing as a generic 500.</li>
 *   <li>SMS not configured: throws {@link OtpDeliveryException}, unless the
 *       developer opt-in {@code app.otp.dev-log-code} (OTP_DEV_LOG_CODE=true) is
 *       set AND the "prod" profile is not active. Then the code is written to
 *       the backend log, marked [OTP-DEV], so local registration can be
 *       completed without an SMS account. It is never logged otherwise.</li>
 * </ul>
 *
 * The message text can be overridden with {@code app.otp.sms-template}
 * (OTP_SMS_TEMPLATE), placeholders {otp}, {purpose}, {minutes}. In India an SMS
 * is delivered only if its text matches a DLT-registered template exactly.
 *
 * Single attempt, no retry: OTPs expire in 5 minutes and the citizen can
 * request a resend faster than a backoff would complete.
 *
 * Audit GAP-004: {@link #sendOtpByEmail} delivers the same code by e-mail
 * through {@link EmailGatewayClient} (SRS 15.2 "OTP-based mobile/email
 * verification"), with the same explicit-failure and dev-log rules. Audit
 * GAP-003: SMS OTPs are sent as {@link SmsMessageType#OTP}, so the adapter
 * attaches the OTP DLT template ID.
 */
@Service
public class NotificationOtpDeliveryService implements OtpDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationOtpDeliveryService.class);

    /**
     * Matches {@code OtpService.VALIDITY_MINUTES} (5 minutes). Kept as a literal
     * rather than exposing OtpService's private constant; if that value changes
     * this must change with it (documented in PROJECT_INTEGRATION.md Section 6).
     */
    private static final long OTP_VALIDITY_MINUTES = 5;

    static final String DEFAULT_TEMPLATE =
            "Your JanNet AI OTP for {purpose} is {otp}. It expires in {minutes} minutes. "
                    + "Do not share this code with anyone.";

    private final SmsGatewayClient smsGatewayClient;
    private final EmailGatewayClient emailGatewayClient;
    private final boolean devLogCode;
    private final String template;

    public NotificationOtpDeliveryService(SmsGatewayClient smsGatewayClient,
                                          EmailGatewayClient emailGatewayClient,
                                          @Value("${app.otp.dev-log-code:false}") boolean devLogCodeRequested,
                                          @Value("${app.otp.sms-template:}") String configuredTemplate,
                                          Environment environment) {
        this.smsGatewayClient = smsGatewayClient;
        this.emailGatewayClient = emailGatewayClient;
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        if (devLogCodeRequested && prod) {
            log.error("OTP_DEV_LOG_CODE=true is IGNORED under the prod profile - OTP codes are never logged in production.");
        }
        this.devLogCode = devLogCodeRequested && !prod;
        this.template = (configuredTemplate == null || configuredTemplate.isBlank()) ? DEFAULT_TEMPLATE : configuredTemplate;
        if (!this.template.contains("{otp}")) {
            // Fail fast at startup: a template without the code would "succeed" while sending no OTP.
            throw new IllegalStateException("app.otp.sms-template (OTP_SMS_TEMPLATE) must contain the {otp} placeholder");
        }
    }

    @Override
    public void sendOtp(String mobileNumber, String otpCode, String purposeLabel) {
        String purpose = purposeLabel == null ? "verification" : purposeLabel.toLowerCase().replace('_', ' ');
        if (!smsGatewayClient.isConfigured()) {
            if (devLogCode) {
                log.warn("[OTP-DEV] SMS not configured ({}); DEVELOPMENT ONLY - {} OTP for {} is {}",
                        smsGatewayClient.missingConfiguration(), purpose, PiiMask.phone(mobileNumber), otpCode);
                return;
            }
            log.error("OTP_DELIVERY_FAILED purpose={} to={} reason=SMS not configured ({})",
                    purpose, PiiMask.phone(mobileNumber), smsGatewayClient.missingConfiguration());
            throw new OtpDeliveryException("SMS delivery is not configured");
        }
        String message = render(otpCode, purpose);
        try {
            smsGatewayClient.send(mobileNumber, message, SmsMessageType.OTP);
        } catch (NotificationDeliveryException e) {
            log.error("OTP_DELIVERY_FAILED purpose={} to={} reason={}", purpose, PiiMask.phone(mobileNumber), e.getMessage());
            throw new OtpDeliveryException("SMS provider did not accept the OTP message", e);
        }
        log.info("OTP_SMS_ACCEPTED purpose={} to={} (accepted by the provider; handset delivery is not confirmed by this API)",
                purpose, PiiMask.phone(mobileNumber));
    }

    @Override
    public boolean isEmailOtpAvailable() {
        return emailGatewayClient.isConfigured();
    }

    @Override
    public void sendOtpByEmail(String emailAddress, String otpCode, String purposeLabel) {
        String purpose = purposeLabel == null ? "verification" : purposeLabel.toLowerCase().replace('_', ' ');
        if (!emailGatewayClient.isConfigured()) {
            if (devLogCode) {
                log.warn("[OTP-DEV] E-mail not configured (NOTIFICATION_EMAIL_ENABLED=false); DEVELOPMENT ONLY - {} OTP for {} is {}",
                        purpose, PiiMask.email(emailAddress), otpCode);
                return;
            }
            log.error("OTP_DELIVERY_FAILED channel=EMAIL purpose={} to={} reason=e-mail not configured",
                    purpose, PiiMask.email(emailAddress));
            throw new OtpDeliveryException("E-mail delivery is not configured");
        }
        try {
            DeliveryOutcome outcome = emailGatewayClient.send(emailAddress, "JanNet AI verification code", render(otpCode, purpose));
            if (outcome != DeliveryOutcome.SENT) {
                throw new OtpDeliveryException("E-mail delivery is not configured");
            }
        } catch (NotificationDeliveryException e) {
            log.error("OTP_DELIVERY_FAILED channel=EMAIL purpose={} to={} reason={}", purpose, PiiMask.email(emailAddress), e.getMessage());
            throw new OtpDeliveryException("The mail server did not accept the OTP message", e);
        }
        log.info("OTP_EMAIL_ACCEPTED purpose={} to={} (accepted by the SMTP server; mailbox delivery is not confirmed)",
                purpose, PiiMask.email(emailAddress));
    }

    private String render(String otpCode, String purpose) {
        return template
                .replace("{otp}", otpCode)
                .replace("{purpose}", purpose)
                .replace("{minutes}", String.valueOf(OTP_VALIDITY_MINUTES));
    }
}
