package com.jannetai.backend.service.notification;

import com.jannetai.backend.exception.OtpDeliveryException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Registration OTP fix: OTP delivery never reports success for an OTP that was not sent. */
@ExtendWith(MockitoExtension.class)
class NotificationOtpDeliveryServiceTest {

    @Mock private SmsGatewayClient smsGatewayClient;
    @Mock private EmailGatewayClient emailGatewayClient;

    private NotificationOtpDeliveryService service(boolean devLogCode, String template, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new NotificationOtpDeliveryService(smsGatewayClient, emailGatewayClient, devLogCode, template, env);
    }

    @Test
    void configuredSmsSendsTheCodeToTheRegisteredNumber() {
        when(smsGatewayClient.isConfigured()).thenReturn(true);

        service(false, "", "dev").sendOtp("9876543210", "123456", "REGISTRATION");

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(smsGatewayClient).send(eq("9876543210"), message.capture(), eq(SmsMessageType.OTP));
        assertThat(message.getValue()).contains("123456").contains("registration").contains("5 minutes");
    }

    @Test
    void customTemplateIsUsedSoTheTextCanMatchARegisteredTemplate() {
        when(smsGatewayClient.isConfigured()).thenReturn(true);

        service(false, "{otp} is your JanNet AI code for {purpose}. Valid {minutes} min.", "dev")
                .sendOtp("9876543210", "654321", "PASSWORD_RESET");

        verify(smsGatewayClient).send("9876543210", "654321 is your JanNet AI code for password reset. Valid 5 min.",
                SmsMessageType.OTP);
    }

    @Test
    void templateWithoutTheOtpPlaceholderIsRejectedAtStartup() {
        assertThatThrownBy(() -> service(false, "Your JanNet AI code is ready.", "dev"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("{otp}");
    }

    @Test
    void providerFailureIsReportedAsOtpDeliveryFailure() {
        when(smsGatewayClient.isConfigured()).thenReturn(true);
        doThrow(new NotificationDeliveryException("SMS provider rejected the message with HTTP 401"))
                .when(smsGatewayClient).send(anyString(), anyString(), any());

        assertThatThrownBy(() -> service(false, "", "dev").sendOtp("9876543210", "123456", "REGISTRATION"))
                .isInstanceOf(OtpDeliveryException.class)
                .hasCauseInstanceOf(NotificationDeliveryException.class);
    }

    @Test
    void missingSmsConfigurationFailsInsteadOfPretendingSuccess() {
        when(smsGatewayClient.isConfigured()).thenReturn(false);
        when(smsGatewayClient.missingConfiguration()).thenReturn("NOTIFICATION_SMS_ENABLED=false");

        assertThatThrownBy(() -> service(false, "", "dev").sendOtp("9876543210", "123456", "REGISTRATION"))
                .isInstanceOf(OtpDeliveryException.class)
                .hasMessageContaining("not configured");
        verify(smsGatewayClient, never()).send(any(), any(), any());
    }

    @Test
    void developerModeAllowsLocalRegistrationWithoutSmsOutsideProd() {
        when(smsGatewayClient.isConfigured()).thenReturn(false);
        when(smsGatewayClient.missingConfiguration()).thenReturn("NOTIFICATION_SMS_ENABLED=false");

        service(true, "", "dev").sendOtp("9876543210", "123456", "REGISTRATION"); // no exception

        verify(smsGatewayClient, never()).send(any(), any(), any());
    }

    @Test
    void developerModeIsIgnoredUnderTheProdProfile() {
        when(smsGatewayClient.isConfigured()).thenReturn(false);
        when(smsGatewayClient.missingConfiguration()).thenReturn("SMS_PROVIDER_URL empty");

        assertThatThrownBy(() -> service(true, "", "prod").sendOtp("9876543210", "123456", "REGISTRATION"))
                .isInstanceOf(OtpDeliveryException.class);
    }

    // ---- Audit GAP-004: e-mail OTP channel ----

    @Test
    void emailOtpIsSentToTheAccountAddressWhenEmailIsConfigured() {
        when(emailGatewayClient.isConfigured()).thenReturn(true);
        when(emailGatewayClient.send(anyString(), anyString(), anyString())).thenReturn(DeliveryOutcome.SENT);

        service(false, "", "dev").sendOtpByEmail("asha@example.org", "246810", "REGISTRATION");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailGatewayClient).send(eq("asha@example.org"), anyString(), body.capture());
        assertThat(body.getValue()).contains("246810").contains("registration");
        verify(smsGatewayClient, never()).send(any(), any(), any());
    }

    @Test
    void emailOtpFailsExplicitlyWhenEmailIsNotConfigured() {
        when(emailGatewayClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service(false, "", "dev").sendOtpByEmail("asha@example.org", "246810", "REGISTRATION"))
                .isInstanceOf(OtpDeliveryException.class)
                .hasMessageContaining("not configured");
        verify(emailGatewayClient, never()).send(any(), any(), any());
    }

    @Test
    void smtpFailureIsReportedAsOtpDeliveryFailure() {
        when(emailGatewayClient.isConfigured()).thenReturn(true);
        when(emailGatewayClient.send(anyString(), anyString(), anyString()))
                .thenThrow(new NotificationDeliveryException("SMTP down"));

        assertThatThrownBy(() -> service(false, "", "dev").sendOtpByEmail("asha@example.org", "246810", "REGISTRATION"))
                .isInstanceOf(OtpDeliveryException.class)
                .hasCauseInstanceOf(NotificationDeliveryException.class);
    }
}
