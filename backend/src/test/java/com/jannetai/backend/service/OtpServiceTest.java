package com.jannetai.backend.service;

import com.jannetai.backend.entity.OtpVerification;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.OtpPurpose;
import com.jannetai.backend.exception.InvalidOtpException;
import com.jannetai.backend.exception.OtpDeliveryException;
import com.jannetai.backend.repository.OtpVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Registration OTP fix: OTP generation, persistence, delivery hand-off,
 * expiry and verification (OtpService previously had no unit tests).
 */
@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock private OtpVerificationRepository otpVerificationRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private OtpDeliveryService otpDeliveryService;

    private OtpService otpService;
    private final User user = User.builder().userId(7L).mobileNumber("9876543210").build();

    @BeforeEach
    void setUp() {
        otpService = new OtpService(otpVerificationRepository, passwordEncoder, otpDeliveryService);
    }

    @Test
    void issuesASixDigitCodeStoresOnlyItsHashAndSendsTheSameCodeToTheRegisteredNumber() {
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "hash:" + inv.getArgument(0));

        otpService.issueAndSend(user, "9876543210", OtpPurpose.REGISTRATION, "127.0.0.1");

        ArgumentCaptor<String> sentCode = ArgumentCaptor.forClass(String.class);
        verify(otpDeliveryService).sendOtp(eq("9876543210"), sentCode.capture(), eq("REGISTRATION"));
        assertThat(sentCode.getValue()).matches("\\d{6}");

        ArgumentCaptor<OtpVerification> saved = ArgumentCaptor.forClass(OtpVerification.class);
        verify(otpVerificationRepository).save(saved.capture());
        OtpVerification otp = saved.getValue();
        assertThat(otp.getOtpCodeHash()).isEqualTo("hash:" + sentCode.getValue());
        assertThat(otp.getOtpCodeHash()).isNotEqualTo(sentCode.getValue());
        assertThat(otp.getMobileNumber()).isEqualTo("9876543210");
        assertThat(otp.getUser()).isSameAs(user);
        assertThat(otp.getPurpose()).isEqualTo(OtpPurpose.REGISTRATION);
        assertThat(otp.getAttemptCount()).isZero();
        assertThat(otp.getExpiresAt()).isCloseTo(LocalDateTime.now().plusMinutes(5), within(5, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void otpIsPersistedBeforeDeliveryAndADeliveryFailurePropagates() {
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        doThrow(new OtpDeliveryException("SMS delivery is not configured"))
                .when(otpDeliveryService).sendOtp(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> otpService.issueAndSend(user, "9876543210", OtpPurpose.REGISTRATION, null))
                .isInstanceOf(OtpDeliveryException.class);

        InOrder order = inOrder(otpVerificationRepository, otpDeliveryService);
        order.verify(otpVerificationRepository).save(any(OtpVerification.class));
        order.verify(otpDeliveryService).sendOtp(anyString(), anyString(), anyString());
    }

    private OtpVerification pending(LocalDateTime expiresAt, int attempts) {
        return OtpVerification.builder()
                .mobileNumber("9876543210").purpose(OtpPurpose.REGISTRATION)
                .otpCodeHash("hash").attemptCount(attempts).maxAttempts(5).expiresAt(expiresAt)
                .build();
    }

    @Test
    void correctCodeIsAcceptedAndConsumed() {
        OtpVerification otp = pending(LocalDateTime.now().plusMinutes(4), 0);
        when(otpVerificationRepository.findFirstByMobileNumberAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                "9876543210", OtpPurpose.REGISTRATION)).thenReturn(Optional.of(otp));
        when(passwordEncoder.matches("123456", "hash")).thenReturn(true);

        otpService.verifyAndConsume("9876543210", OtpPurpose.REGISTRATION, "123456");

        assertThat(otp.getConsumedAt()).isNotNull();
        assertThat(otp.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void expiredCodeIsRejectedWithoutCheckingIt() {
        OtpVerification otp = pending(LocalDateTime.now().minusSeconds(1), 0);
        when(otpVerificationRepository.findFirstByMobileNumberAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                "9876543210", OtpPurpose.REGISTRATION)).thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> otpService.verifyAndConsume("9876543210", OtpPurpose.REGISTRATION, "123456"))
                .isInstanceOf(InvalidOtpException.class)
                .hasMessageContaining("expired");
        verify(passwordEncoder, never()).matches(any(), any());
        assertThat(otp.getConsumedAt()).isNull();
    }

    @Test
    void wrongCodeIsRejectedAndCountsAsAnAttempt() {
        OtpVerification otp = pending(LocalDateTime.now().plusMinutes(4), 2);
        when(otpVerificationRepository.findFirstByMobileNumberAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                "9876543210", OtpPurpose.REGISTRATION)).thenReturn(Optional.of(otp));
        when(passwordEncoder.matches("000000", "hash")).thenReturn(false);

        assertThatThrownBy(() -> otpService.verifyAndConsume("9876543210", OtpPurpose.REGISTRATION, "000000"))
                .isInstanceOf(InvalidOtpException.class);
        assertThat(otp.getAttemptCount()).isEqualTo(3);
        assertThat(otp.getConsumedAt()).isNull();
    }

    @Test
    void noPendingCodeIsRejected() {
        when(otpVerificationRepository.findFirstByMobileNumberAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                "9876543210", OtpPurpose.REGISTRATION)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> otpService.verifyAndConsume("9876543210", OtpPurpose.REGISTRATION, "123456"))
                .isInstanceOf(InvalidOtpException.class);
    }
}
