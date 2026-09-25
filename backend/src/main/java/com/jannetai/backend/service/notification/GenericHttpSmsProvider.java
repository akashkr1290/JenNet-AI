package com.jannetai.backend.service.notification;

import com.jannetai.backend.config.NotificationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Audit GAP-003: the configuration-driven HTTP SMS adapter ("generic-http",
 * the default {@code SMS_PROVIDER}). The request is built by
 * {@link SmsRequestBuilder} from {@code app.notification.sms.*}; see
 * docs/SMS_PROVIDER_CONFIGURATION.md for the settings and how to map a
 * provider's documented API onto them.
 *
 * Success means an HTTP 2xx response AND, when {@code SMS_PROVIDER_SUCCESS_PATTERN}
 * is set, a response body matching that regular expression (several providers
 * answer 200 with an error in the body). "Success" is acceptance by the
 * provider - handset delivery is not reported by this API.
 */
@Component
public class GenericHttpSmsProvider implements SmsProvider {

    public static final String ID = "generic-http";

    private final RestTemplate restTemplate;

    @Autowired
    public GenericHttpSmsProvider(NotificationProperties properties) {
        this(buildRestTemplate(properties.getSms()));
    }

    /** Test seam: lets unit tests bind a MockRestServiceServer. */
    GenericHttpSmsProvider(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private static RestTemplate buildRestTemplate(NotificationProperties.Sms sms) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(sms.getConnectTimeoutMs());
        factory.setReadTimeout(sms.getReadTimeoutMs());
        return new RestTemplate(factory);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String missingConfiguration(NotificationProperties.Sms sms) {
        StringJoiner missing = new StringJoiner(", ");
        if (sms.getProviderUrl() == null || sms.getProviderUrl().isBlank()) {
            missing.add("SMS_PROVIDER_URL empty");
        }
        try {
            String credentials = SmsRequestBuilder.missingCredentials(
                    SmsRequestBuilder.parseAuthScheme(sms.getAuthScheme()), sms.getProviderApiKey(), sms.getBasicUsername());
            if (!credentials.isEmpty()) {
                missing.add(credentials);
            }
            SmsRequestBuilder.parseFormat(sms.getRequestFormat());
            SmsRequestBuilder.parseNumberFormat(sms.getNumberFormat());
        } catch (IllegalArgumentException e) {
            missing.add(e.getMessage());
        }
        if (sms.getSuccessBodyPattern() != null && !sms.getSuccessBodyPattern().isBlank()) {
            try {
                Pattern.compile(sms.getSuccessBodyPattern());
            } catch (PatternSyntaxException e) {
                missing.add("SMS_PROVIDER_SUCCESS_PATTERN is not a valid regular expression");
            }
        }
        return missing.toString();
    }

    @Override
    public void send(NotificationProperties.Sms sms, String e164Number, String message, SmsMessageType type) {
        SmsRequestBuilder.Request request = SmsRequestBuilder.build(settingsFor(sms, type), e164Number, message);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(request.contentType()));
        request.headers().forEach(headers::set);
        ResponseEntity<String> response;
        try {
            // URI (not String) so the already-encoded query is not re-expanded as a URI template.
            response = restTemplate.exchange(URI.create(request.url()), HttpMethod.POST,
                    new HttpEntity<>(request.body(), headers), String.class);
        } catch (HttpStatusCodeException e) {
            throw new NotificationDeliveryException(
                    "SMS provider rejected the message with HTTP " + e.getStatusCode().value()
                            + " (to " + PiiMask.phone(e164Number) + ")", e);
        } catch (ResourceAccessException e) {
            throw new NotificationDeliveryException(
                    "SMS provider unreachable or timed out (host " + hostOf(sms.getProviderUrl()) + ")", e);
        }
        String pattern = sms.getSuccessBodyPattern();
        if (pattern != null && !pattern.isBlank()) {
            String body = response.getBody() == null ? "" : response.getBody();
            if (!Pattern.compile(pattern).matcher(body).find()) {
                throw new NotificationDeliveryException("SMS provider answered HTTP "
                        + response.getStatusCode().value() + " but the body did not match SMS_PROVIDER_SUCCESS_PATTERN (to "
                        + PiiMask.phone(e164Number) + ")");
            }
        }
    }

    static SmsRequestBuilder.Settings settingsFor(NotificationProperties.Sms sms, SmsMessageType type) {
        String templateId = type == SmsMessageType.OTP ? sms.getOtpDltTemplateId() : sms.getNotificationDltTemplateId();
        return new SmsRequestBuilder.Settings(
                sms.getProviderUrl(),
                SmsRequestBuilder.parseFormat(sms.getRequestFormat()),
                SmsRequestBuilder.parseAuthScheme(sms.getAuthScheme()),
                sms.getProviderApiKey(),
                sms.getAuthHeaderName(),
                sms.getAuthQueryParam(),
                sms.getBasicUsername(),
                SmsRequestBuilder.parseNumberFormat(sms.getNumberFormat()),
                sms.getDefaultCountryCode(),
                sms.getToField(),
                sms.getMessageField(),
                sms.getSenderIdField(),
                sms.getSenderId(),
                sms.getDltEntityIdField(),
                sms.getDltEntityId(),
                sms.getDltTemplateIdField(),
                templateId,
                sms.getExtraParams());
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
