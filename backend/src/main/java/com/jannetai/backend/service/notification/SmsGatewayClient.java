package com.jannetai.backend.service.notification;

import com.jannetai.backend.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Real HTTP-based SMS delivery for the Notification Module (Phase 15, SRS
 * 15.13) and, replacing {@code LoggingOtpDeliveryService}, for OTP
 * delivery (SRS 15.2/15.6) - see {@link NotificationOtpDeliveryService}.
 * No specific SMS provider is named anywhere in the SRS ("external SMS/
 * email/push gateways", 15.13 Dependencies, is generic), so this posts a
 * minimal generic JSON body ({@code {"to": ..., "message": ...}}) with the
 * provider API key as a bearer token to a configurable URL
 * ({@code app.notification.sms.provider-url}) - a real deployment's
 * actual provider contract may need a thin adapter here, but the shape of
 * "POST to a configured URL with a bearer token" is provider-agnostic
 * enough to cover most SMS gateway REST APIs without guessing one
 * specific vendor's exact schema.
 *
 * Gated by {@code app.notification.sms.enabled}, same "off by default in
 * an unconfigured sandbox, logs a stub instead" convention as
 * {@link EmailGatewayClient} - see that class's Javadoc.
 *
 * Reuses the plain {@link RestTemplate} bean already configured by
 * {@code RestTemplateConfig} for ai-service calls (Phase 8) rather than
 * introducing a second HTTP client stack - a new {@link RestTemplate}
 * instance is built directly here (not autowired) so this class's
 * timeouts are independent of ai-service's, without adding a second named
 * bean/qualifier to that existing configuration class.
 */
@Component
public class SmsGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(SmsGatewayClient.class);

    private final NotificationProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();

    public SmsGatewayClient(NotificationProperties properties) {
        this.properties = properties;
    }

    /**
     * @throws NotificationDeliveryException on any transport/non-2xx
     *         failure - never thrown merely because the channel is
     *         disabled (see class Javadoc).
     */
    public void send(String mobileNumber, String message) {
        NotificationProperties.Sms sms = properties.getSms();
        if (!sms.isEnabled() || sms.getProviderUrl() == null || sms.getProviderUrl().isBlank()) {
            log.warn("[SMS-STUB] Would send SMS to {}: {} - app.notification.sms.enabled is false "
                    + "or no provider URL is configured.", mobileNumber, message);
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(sms.getProviderApiKey());
            HttpEntity<Map<String, String>> request = new HttpEntity<>(
                    Map.of("to", mobileNumber, "message", message), headers);
            restTemplate.exchange(sms.getProviderUrl(), HttpMethod.POST, request, String.class);
        } catch (HttpStatusCodeException e) {
            throw new NotificationDeliveryException(
                    "SMS provider returned " + e.getStatusCode() + " for " + mobileNumber, e);
        } catch (ResourceAccessException e) {
            throw new NotificationDeliveryException(
                    "SMS provider unreachable at " + sms.getProviderUrl() + ": " + e.getMessage(), e);
        }
    }
}
