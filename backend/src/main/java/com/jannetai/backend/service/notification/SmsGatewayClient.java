package com.jannetai.backend.service.notification;

import com.jannetai.backend.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;

/**
 * HTTP-based SMS delivery for the Notification Module (Phase 15, SRS 15.13)
 * and, through {@link NotificationOtpDeliveryService}, for OTP delivery
 * (SRS 15.2/15.6).
 *
 * PROVIDER CONTRACT. No specific SMS vendor is implemented; the SRS names none
 * ("external SMS/email/push gateways", 15.13 Dependencies). This client POSTs
 * {@code {"to": "+<E.164 number>", "message": "..."}} as JSON with
 * {@code Authorization: Bearer <SMS_PROVIDER_API_KEY>} to {@code SMS_PROVIDER_URL}
 * and treats any 2xx response as "accepted by the provider". A vendor whose API
 * differs (for example form-encoded bodies, basic auth, or a template/flow id)
 * needs a thin relay in front of it that accepts this contract - see
 * docs/REGISTRATION_OTP_SMS.md.
 *
 * When SMS is not configured ({@link #isConfigured()} is false), {@link #send}
 * keeps its original behaviour for best-effort notifications: it logs a stub
 * line and returns. OTP delivery does not rely on that - it checks
 * {@link #isConfigured()} first and fails explicitly (registration OTP fix).
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
 * A dedicated {@link RestTemplate} is built here (not the ai-service bean from
 * RestTemplateConfig) so these timeouts are independent of ai-service's.
 */
@Component
public class SmsGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(SmsGatewayClient.class);

    private final NotificationProperties properties;
    private final RestTemplate restTemplate;

    @Autowired
    public SmsGatewayClient(NotificationProperties properties) {
        this(properties, buildRestTemplate(properties.getSms()));
    }

    /** Test seam: lets unit tests bind a MockRestServiceServer to this client's RestTemplate. */
    SmsGatewayClient(NotificationProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    private static RestTemplate buildRestTemplate(NotificationProperties.Sms sms) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(sms.getConnectTimeoutMs());
        factory.setReadTimeout(sms.getReadTimeoutMs());
        return new RestTemplate(factory);
    }

    /** True only when SMS is enabled AND both the provider URL and the API key are set. */
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
        if (sms.getProviderUrl() == null || sms.getProviderUrl().isBlank()) {
            missing.append(missing.length() > 0 ? ", " : "").append("SMS_PROVIDER_URL empty");
        }
        if (sms.getProviderApiKey() == null || sms.getProviderApiKey().isBlank()) {
            missing.append(missing.length() > 0 ? ", " : "").append("SMS_PROVIDER_API_KEY empty");
        }
        return missing.toString();
    }

    /**
     * @throws NotificationDeliveryException on an invalid number, any non-2xx
     *         provider response, or an unreachable/timed-out provider - never
     *         thrown merely because the channel is not configured (see class Javadoc).
     */
    public void send(String mobileNumber, String message) {
        NotificationProperties.Sms sms = properties.getSms();
        if (!isConfigured()) {
            // Remaining-gaps item 15: number masked, message not logged (length only).
            log.warn("[SMS-STUB] Would send SMS to {} ({} chars) - SMS not configured ({}).",
                    PiiMask.phone(mobileNumber), message == null ? 0 : message.length(), missingConfiguration());
            return;
        }
        String to;
        try {
            to = SmsNumberFormatter.toInternational(mobileNumber, sms.getDefaultCountryCode());
        } catch (IllegalArgumentException e) {
            throw new NotificationDeliveryException(
                    "Invalid mobile number for SMS (" + PiiMask.phone(mobileNumber) + "): " + e.getMessage(), e);
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(sms.getProviderApiKey());
            HttpEntity<Map<String, String>> request = new HttpEntity<>(Map.of("to", to, "message", message), headers);
            restTemplate.exchange(sms.getProviderUrl(), HttpMethod.POST, request, String.class);
        } catch (HttpStatusCodeException e) {
            throw new NotificationDeliveryException(
                    "SMS provider rejected the message with HTTP " + e.getStatusCode().value()
                            + " (to " + PiiMask.phone(to) + ")", e);
        } catch (ResourceAccessException e) {
            throw new NotificationDeliveryException(
                    "SMS provider unreachable or timed out (host " + hostOf(sms.getProviderUrl()) + ")", e);
        }
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "unknown" : host;
        } catch (IllegalArgumentException e) {
            return "invalid URL";
        }
    }
}
