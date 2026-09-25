package com.jannetai.backend.service.notification;

import com.jannetai.backend.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Registration OTP fix: SMS provider request construction and failure handling.
 * The provider is simulated with MockRestServiceServer - no real SMS is sent.
 */
class SmsGatewayClientTest {

    private static final String URL = "https://sms.example.test/v1/send";

    private NotificationProperties properties;
    private MockRestServiceServer server;
    private SmsGatewayClient client;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.getSms().setEnabled(true);
        properties.getSms().setProviderUrl(URL);
        properties.getSms().setProviderApiKey("test-api-key");
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new SmsGatewayClient(properties, restTemplate);
    }

    @Test
    void sendsInternationalNumberAndMessageWithBearerKey() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-api-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"to\":\"+919876543210\",\"message\":\"hello\"}"))
                .andRespond(withSuccess("{\"status\":\"queued\"}", MediaType.APPLICATION_JSON));

        client.send("9876543210", "hello");
        server.verify();
    }

    @Test
    void providerErrorIsRaisedNotSwallowedAndDoesNotLeakNumberOrKey() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.send("9876543210", "hello"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageNotContaining("9876543210")
                .hasMessageNotContaining("test-api-key");
    }

    @Test
    void invalidNumberFailsBeforeAnyProviderCall() {
        assertThatThrownBy(() -> client.send("12345", "hello"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("Invalid mobile number");
        server.verify(); // no request was expected, so none may have been made
    }

    @Test
    void configurationIsIncompleteWhenDisabledOrUrlOrKeyMissing() {
        assertThat(client.isConfigured()).isTrue();

        properties.getSms().setProviderApiKey("");
        assertThat(client.isConfigured()).isFalse();
        assertThat(client.missingConfiguration()).isEqualTo("SMS_PROVIDER_API_KEY empty");

        properties.getSms().setEnabled(false);
        properties.getSms().setProviderUrl("");
        assertThat(client.missingConfiguration())
                .isEqualTo("NOTIFICATION_SMS_ENABLED=false, SMS_PROVIDER_URL empty, SMS_PROVIDER_API_KEY empty");
    }

    @Test
    void unconfiguredClientStubsInsteadOfCallingTheProvider() {
        properties.getSms().setEnabled(false);
        // best-effort notification behaviour is unchanged, but it is now reported as SKIPPED (audit GAP-022)
        assertThat(client.send("9876543210", "hello")).isEqualTo(DeliveryOutcome.SKIPPED);
        server.verify(); // and no HTTP request was made
    }

    // ---- Audit GAP-003: configuration-driven provider adapter (mocked provider, no real SMS) ----

    @Test
    void acceptedMessageIsReportedAsSent() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"status\":\"queued\"}", MediaType.APPLICATION_JSON));

        assertThat(client.send("9876543210", "hello")).isEqualTo(DeliveryOutcome.SENT);
        server.verify();
    }

    @Test
    void formEncodedBasicAuthRequestCarriesSenderAndDltIdsForOtp() {
        NotificationProperties.Sms sms = properties.getSms();
        sms.setRequestFormat("FORM");
        sms.setAuthScheme("BASIC");
        sms.setBasicUsername("acct");
        sms.setNumberFormat("DIGITS");
        sms.setSenderIdField("sender");
        sms.setSenderId("JANNET");
        sms.setDltEntityIdField("entity_id");
        sms.setDltEntityId("1101");
        sms.setDltTemplateIdField("template_id");
        sms.setOtpDltTemplateId("T-OTP");
        sms.setNotificationDltTemplateId("T-NOTIFY");
        String basic = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("acct:test-api-key".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basic))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(
                        "to=919876543210&message=code+123456&sender=JANNET&entity_id=1101&template_id=T-OTP"))
                .andRespond(withSuccess());

        client.send("9876543210", "code 123456", SmsMessageType.OTP);
        server.verify();
    }

    @Test
    void customHeaderAuthUsesTheConfiguredHeaderName() {
        properties.getSms().setAuthScheme("HEADER");
        properties.getSms().setAuthHeaderName("X-Api-Key");
        server.expect(requestTo(URL)).andExpect(header("X-Api-Key", "test-api-key")).andRespond(withSuccess());

        client.send("9876543210", "hello");
        server.verify();
    }

    @Test
    void http200WithAnErrorBodyIsAFailureWhenASuccessPatternIsConfigured() {
        properties.getSms().setSuccessBodyPattern("\"status\"\\s*:\\s*\"(queued|sent)\"");
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"status\":\"error\",\"reason\":\"template mismatch\"}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.send("9876543210", "hello"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("SMS_PROVIDER_SUCCESS_PATTERN")
                .hasMessageNotContaining("9876543210");
    }

    @Test
    void unknownProviderIdMakesSmsUnconfiguredInsteadOfSendingSomewhereElse() {
        properties.getSms().setProvider("some-vendor");

        assertThat(client.isConfigured()).isFalse();
        assertThat(client.missingConfiguration()).contains("SMS_PROVIDER=some-vendor has no adapter");
        assertThat(client.send("9876543210", "hello")).isEqualTo(DeliveryOutcome.SKIPPED);
        server.verify();
    }

    @Test
    void authSchemeNoneNeedsNoApiKey() {
        properties.getSms().setAuthScheme("NONE");
        properties.getSms().setProviderApiKey("");

        assertThat(client.isConfigured()).isTrue();
    }
}
