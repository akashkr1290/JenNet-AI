package com.jannetai.backend.service.notification;

import com.jannetai.backend.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * HTTP-based SMS delivery for the Notification Module (Phase 15, SRS 15.13)
 * and, through {@link NotificationOtpDeliveryService}, for OTP delivery
 * (SRS 15.2/15.6).
 *
 * PROVIDER CONTRACT. No specific SMS vendor is implemented; the SRS names none
 * ("external SMS/email/push gateways", 15.13 Dependencies). Audit GAP-003: the
 * transmission itself is delegated to an {@link SmsProvider} adapter chosen by
 * {@code SMS_PROVIDER}. The default "generic-http" adapter
 * ({@link GenericHttpSmsProvider}) is configured entirely through environment
 * variables - JSON or form body, Bearer/custom-header/Basic/query-parameter
 * authentication, field names, sender ID and the Indian DLT entity/template IDs
 * (docs/SMS_PROVIDER_CONFIGURATION.md). With the defaults it still POSTs
 * {@code {"to": "+<E.164 number>", "message": "..."}} with
 * {@code Authorization: Bearer <SMS_PROVIDER_API_KEY>}, exactly as before.
 *
 * When SMS is not configured ({@link #isConfigured()} is false), {@link #send}
 * keeps its original behaviour for best-effort notifications: it logs a stub
 * line and returns - now with {@link DeliveryOutcome#SKIPPED} (audit GAP-022),
 * so the notification log no longer records DELIVERED for it. OTP delivery does
 * not rely on that - it checks {@link #isConfigured()} first and fails
 * explicitly (registration OTP fix).
 *
 * Registration OTP fix, also:
 * <ul>
 *   <li>numbers are sent in international form ({@link SmsNumberFormatter});
 *       previously the stored 10-digit national number was sent as-is;</li>
 *   <li>connect/read timeouts (the provider call previously had none, so a hung
 *       provider blocked the registration request indefinitely);</li>
 *   <li>"configured" now also requires the API key, not only the URL;</li>
 *   <li>exception messages mask the number and name only the provider host,
 *       never the full URL (which may carry a token).</li>
 * </ul>
 *
 * The generic-http adapter builds its own {@link RestTemplate} (not the
 * ai-service bean from RestTemplateConfig) so its timeouts are independent.
 */
@Component
public class SmsGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(SmsGatewayClient.class);

    private final NotificationProperties properties;
    private final List<SmsProvider> providers;

    @Autowired
    public SmsGatewayClient(NotificationProperties properties, List<SmsProvider> providers) {
        this.properties = properties;
        this.providers = List.copyOf(providers);
    }

    /** Test seam: the generic-http adapter bound to the given (mockable) RestTemplate. */
    SmsGatewayClient(NotificationProperties properties, RestTemplate restTemplate) {
        this(properties, List.of(new GenericHttpSmsProvider(restTemplate)));
    }

    /** True only when SMS is enabled AND the selected adapter has everything it needs. */
    public boolean isConfigured() {
        return missingConfiguration().isEmpty();
    }

    /**
     * Names of the missing or disabled settings, for operator-facing logs.
     * Only variable names are returned - never values.
     */
    public String missingConfiguration() {
        NotificationProperties.Sms sms = properties.getSms();
        StringBuilder missing = new StringBuilder();
        if (!sms.isEnabled()) {
            missing.append("NOTIFICATION_SMS_ENABLED=false");
        }
        SmsProvider provider = selectedProvider();
        String providerMissing = provider == null
                ? "SMS_PROVIDER=" + sms.getProvider() + " has no adapter (available: " + availableIds() + ")"
                : provider.missingConfiguration(sms);
        if (!providerMissing.isEmpty()) {
            missing.append(missing.length() > 0 ? ", " : "").append(providerMissing);
        }
        return missing.toString();
    }

    /** Service notification text (DLT notification template). */
    public DeliveryOutcome send(String mobileNumber, String message) {
        return send(mobileNumber, message, SmsMessageType.NOTIFICATION);
    }

    /**
     * @return {@link DeliveryOutcome#SENT} when the provider accepted the message,
     *         {@link DeliveryOutcome#SKIPPED} when SMS is not configured
     * @throws NotificationDeliveryException on an invalid number, a provider
     *         rejection, or an unreachable/timed-out provider - never thrown
     *         merely because the channel is not configured (see class Javadoc).
     */
    public DeliveryOutcome send(String mobileNumber, String message, SmsMessageType type) {
        NotificationProperties.Sms sms = properties.getSms();
        if (!isConfigured()) {
            // Remaining-gaps item 15: number masked, message not logged (length only).
            log.warn("[SMS-STUB] Would send SMS to {} ({} chars) - SMS not configured ({}).",
                    PiiMask.phone(mobileNumber), message == null ? 0 : message.length(), missingConfiguration());
            return DeliveryOutcome.SKIPPED;
        }
        String to;
        try {
            to = SmsNumberFormatter.toInternational(mobileNumber, sms.getDefaultCountryCode());
        } catch (IllegalArgumentException e) {
            throw new NotificationDeliveryException(
                    "Invalid mobile number for SMS (" + PiiMask.phone(mobileNumber) + "): " + e.getMessage(), e);
        }
        selectedProvider().send(sms, to, message, type);
        return DeliveryOutcome.SENT;
    }

    private SmsProvider selectedProvider() {
        String id = properties.getSms().getProvider();
        String wanted = id == null || id.isBlank() ? GenericHttpSmsProvider.ID : id.trim();
        return providers.stream().filter(p -> p.id().equalsIgnoreCase(wanted)).findFirst().orElse(null);
    }

    private String availableIds() {
        return String.join(", ", providers.stream().map(SmsProvider::id).toList());
    }
}
