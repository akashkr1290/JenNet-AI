package com.jannetai.backend.service;

import com.jannetai.backend.dto.auth.ForgotPasswordRequest;
import com.jannetai.backend.dto.auth.LoginRequest;
import com.jannetai.backend.dto.auth.RegisterRequest;
import com.jannetai.backend.dto.auth.ResendOtpRequest;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.OtpPurpose;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.exception.AccountLockedException;
import com.jannetai.backend.exception.AccountSuspendedException;
import com.jannetai.backend.exception.MfaRequiredException;
import com.jannetai.backend.exception.OtpDeliveryException;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.repository.WardRepository;
import com.jannetai.backend.security.JwtService;
import com.jannetai.backend.security.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthService} - SRS 15.2 login/lockout/MFA rules.
 * The account-lockout counter (5 attempts / 15-minute lock) and the
 * Admin/Super-Admin MFA branch are this class's two most safety-critical
 * behaviors, so they get the deepest coverage here.
 *
 * NOT EXECUTED in this workspace (no Maven Central reach - see
 * PROJECT_PROGRESS.md Phase 20 TESTS section). Manually validated against
 * AuthService.java's actual constants (MAX_FAILED_LOGIN_ATTEMPTS=5,
 * LOCKOUT_MINUTES=15) and method/field signatures.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private WardRepository wardRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private OtpService otpService;
    @Mock private JwtService jwtService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private AuditService auditService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository, wardRepository, passwordEncoder, otpService,
                jwtService, refreshTokenService, auditService);
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private User activeUser(Role role) {
        return User.builder()
                .userId(1L)
                .mobileNumber("+911234567890")
                .role(role)
                .status(UserStatus.ACTIVE)
                .passwordHash("hashed")
                .failedLoginCount(0)
                .build();
    }

    // ---- Lockout (SRS 15.2: 5 failed attempts -> 15-minute lock) ----

    @Test
    void wrongPasswordIncrementsFailedLoginCountWithoutLockingBeforeTheFifthAttempt() {
        User user = activeUser(Role.CITIZEN);
        user.setFailedLoginCount(2);
        when(userRepository.findByMobileNumberOrEmail("+911234567890", "+911234567890"))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        LoginRequest request = new LoginRequest("+911234567890", "wrong");

        assertThatThrownBy(() -> authService.login(request, "device", "127.0.0.1"))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(user.getFailedLoginCount()).isEqualTo(3);
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void fifthConsecutiveFailureLocksTheAccountFor15Minutes() {
        User user = activeUser(Role.CITIZEN);
        user.setFailedLoginCount(4); // this will be the 5th failure
        when(userRepository.findByMobileNumberOrEmail("+911234567890", "+911234567890"))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        LoginRequest request = new LoginRequest("+911234567890", "wrong");

        assertThatThrownBy(() -> authService.login(request, "device", "127.0.0.1"))
                .isInstanceOf(BadCredentialsException.class);

        // Counter resets to 0 once locked out (see AuthService.registerFailedAttempt).
        assertThat(user.getFailedLoginCount()).isEqualTo(0);
        assertThat(user.getLockedUntil()).isAfter(LocalDateTime.now().plusMinutes(14));
        assertThat(user.getLockedUntil()).isBefore(LocalDateTime.now().plusMinutes(16));
        verify(auditService).record(user, "ACCOUNT_LOCKED", user.getUserId(), null);
    }

    @Test
    void loginIsRefusedWhileTheAccountIsStillLocked() {
        User user = activeUser(Role.CITIZEN);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(userRepository.findByMobileNumberOrEmail("+911234567890", "+911234567890"))
                .thenReturn(Optional.of(user));

        LoginRequest request = new LoginRequest("+911234567890", "whatever");

        assertThatThrownBy(() -> authService.login(request, "device", "127.0.0.1"))
                .isInstanceOf(AccountLockedException.class);
        // Refused before even checking the password - matches() must never be called.
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void loginSucceedsOnceTheLockoutWindowHasExpired() {
        User user = activeUser(Role.CITIZEN);
        user.setLockedUntil(LocalDateTime.now().minusMinutes(1)); // expired
        user.setFailedLoginCount(5);
        when(userRepository.findByMobileNumberOrEmail("+911234567890", "+911234567890"))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);
        when(refreshTokenService.issueNewFamily(eq(user), any(), any()))
                .thenReturn(new RefreshTokenService.IssuedToken("raw-refresh-token", null));
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");
        when(jwtService.accessTokenExpiryMinutes()).thenReturn(30L);

        LoginRequest request = new LoginRequest("+911234567890", "correct");
        var response = authService.login(request, "device", "127.0.0.1");

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(user.getFailedLoginCount()).isEqualTo(0);
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void suspendedAccountIsRejectedBeforePasswordCheck() {
        User user = activeUser(Role.CITIZEN);
        user.setStatus(UserStatus.SUSPENDED);
        when(userRepository.findByMobileNumberOrEmail("+911234567890", "+911234567890"))
                .thenReturn(Optional.of(user));

        LoginRequest request = new LoginRequest("+911234567890", "whatever");

        assertThatThrownBy(() -> authService.login(request, "device", "127.0.0.1"))
                .isInstanceOf(AccountSuspendedException.class);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void unknownIdentifierIsRejectedAsBadCredentialsNotResourceNotFound() {
        // Deliberate: does not reveal whether the account exists.
        when(userRepository.findByMobileNumberOrEmail("nobody", "nobody")).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest("nobody", "whatever");

        assertThatThrownBy(() -> authService.login(request, "device", "127.0.0.1"))
                .isInstanceOf(BadCredentialsException.class);
    }

    // ---- MFA branching (SRS 27.1: ADMIN/SUPER_ADMIN only) ----

    @Test
    void adminLoginWithCorrectPasswordTriggersMfaInsteadOfIssuingTokens() {
        User admin = activeUser(Role.ADMIN);
        when(userRepository.findByMobileNumberOrEmail("+911234567890", "+911234567890"))
                .thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);
        when(jwtService.generateMfaToken(admin)).thenReturn("mfa-token");

        LoginRequest request = new LoginRequest("+911234567890", "correct");

        assertThatThrownBy(() -> authService.login(request, "device", "127.0.0.1"))
                .isInstanceOf(MfaRequiredException.class)
                .satisfies(ex -> assertThat(((MfaRequiredException) ex).getMfaToken()).isEqualTo("mfa-token"));

        verify(otpService).issueAndSend(admin, admin.getMobileNumber(), OtpPurpose.LOGIN_MFA, "127.0.0.1");
        // Tokens must NOT be issued yet - only after /mfa/verify.
        verify(refreshTokenService, never()).issueNewFamily(any(), any(), any());
    }

    @Test
    void citizenLoginWithCorrectPasswordNeverTriggersMfa() {
        User citizen = activeUser(Role.CITIZEN);
        when(userRepository.findByMobileNumberOrEmail("+911234567890", "+911234567890"))
                .thenReturn(Optional.of(citizen));
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);
        when(refreshTokenService.issueNewFamily(eq(citizen), any(), any()))
                .thenReturn(new RefreshTokenService.IssuedToken("raw-refresh-token", null));
        when(jwtService.generateAccessToken(citizen)).thenReturn("access-token");
        when(jwtService.accessTokenExpiryMinutes()).thenReturn(30L);

        LoginRequest request = new LoginRequest("+911234567890", "correct");
        var response = authService.login(request, "device", "127.0.0.1");

        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(otpService, never()).issueAndSend(any(), any(), eq(OtpPurpose.LOGIN_MFA), any());
    }

    @Test
    void departmentHeadLoginNeverTriggersMfaEither() {
        // requiresMfa is ADMIN/SUPER_ADMIN only - explicit negative check
        // for a third role to guard against an accidental broadening.
        User head = activeUser(Role.DEPARTMENT_HEAD);
        when(userRepository.findByMobileNumberOrEmail("+911234567890", "+911234567890"))
                .thenReturn(Optional.of(head));
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);
        when(refreshTokenService.issueNewFamily(eq(head), any(), any()))
                .thenReturn(new RefreshTokenService.IssuedToken("raw-refresh-token", null));
        when(jwtService.generateAccessToken(head)).thenReturn("access-token");
        when(jwtService.accessTokenExpiryMinutes()).thenReturn(30L);

        LoginRequest request = new LoginRequest("+911234567890", "correct");
        assertThat(authService.login(request, "device", "127.0.0.1").accessToken()).isEqualTo("access-token");
    }

    // ---- Password reset invalidates all sessions ----

    @Test
    void successfulPasswordResetRevokesAllExistingSessions() {
        User user = activeUser(Role.CITIZEN);
        user.setFailedLoginCount(3);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(5));
        var otp = com.jannetai.backend.entity.OtpVerification.builder().user(user).build();
        when(otpService.verifyAndConsume("+911234567890", OtpPurpose.PASSWORD_RESET, "123456"))
                .thenReturn(otp);
        when(passwordEncoder.encode("NewSecurePassword1!")).thenReturn("new-hash");

        var request = new com.jannetai.backend.dto.auth.ResetPasswordRequest(
                "+911234567890", "123456", "NewSecurePassword1!");
        authService.resetPassword(request);

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getFailedLoginCount()).isEqualTo(0);
        assertThat(user.getLockedUntil()).isNull();
        verify(refreshTokenService).revokeAllForUser(user.getUserId());
    }

    // ---- Registration OTP fix: registration / resend / password-reset OTP delivery ----

    private RegisterRequest registration() {
        return new RegisterRequest("Asha Citizen", "9876543210", null, "Str0ng!Pass", null);
    }

    @Test
    void registrationSendsTheRegistrationOtpToTheRegisteredNumber() {
        authService.register(registration(), "127.0.0.1");

        verify(otpService).issueAndSend(any(User.class), eq("9876543210"), eq(OtpPurpose.REGISTRATION), eq("127.0.0.1"));
    }

    @Test
    void registrationFailsWhenTheOtpCannotBeSentInsteadOfReportingSuccess() {
        doThrow(new OtpDeliveryException("SMS delivery is not configured"))
                .when(otpService).issueAndSend(any(User.class), anyString(), eq(OtpPurpose.REGISTRATION), any());

        assertThatThrownBy(() -> authService.register(registration(), "127.0.0.1"))
                .isInstanceOf(OtpDeliveryException.class);
        // The REGISTER audit entry is not written, and (under a real transaction)
        // the new account rolls back with it, so the citizen can register again.
        verifyNoInteractions(auditService);
    }

    @Test
    void resendingARegistrationOtpReportsADeliveryFailure() {
        when(userRepository.findByMobileNumber("9876543210")).thenReturn(Optional.of(activeUser(Role.CITIZEN)));
        doThrow(new OtpDeliveryException("SMS provider did not accept the OTP message"))
                .when(otpService).issueAndSend(any(User.class), eq("9876543210"), eq(OtpPurpose.REGISTRATION), any());

        assertThatThrownBy(() -> authService.resendOtp(
                new ResendOtpRequest("9876543210", OtpPurpose.REGISTRATION), "127.0.0.1"))
                .isInstanceOf(OtpDeliveryException.class);
    }

    @Test
    void passwordResetResendDoesNotRevealAccountExistenceWhenDeliveryFails() {
        when(userRepository.findByMobileNumber("9876543210")).thenReturn(Optional.of(activeUser(Role.CITIZEN)));
        when(userRepository.findByMobileNumber("9000000000")).thenReturn(Optional.empty());
        doThrow(new OtpDeliveryException("SMS delivery is not configured"))
                .when(otpService).issueAndSend(any(User.class), eq("9876543210"), eq(OtpPurpose.PASSWORD_RESET), any());

        // Same outcome as for an unknown number: no exception, generic response.
        authService.resendOtp(new ResendOtpRequest("9876543210", OtpPurpose.PASSWORD_RESET), "127.0.0.1");
        authService.resendOtp(new ResendOtpRequest("9000000000", OtpPurpose.PASSWORD_RESET), "127.0.0.1");
    }

    @Test
    void forgotPasswordDoesNotRevealAccountExistenceWhenDeliveryFails() {
        when(userRepository.findByMobileNumber("9876543210")).thenReturn(Optional.of(activeUser(Role.CITIZEN)));
        doThrow(new OtpDeliveryException("SMS delivery is not configured"))
                .when(otpService).issueAndSend(any(User.class), eq("9876543210"), eq(OtpPurpose.PASSWORD_RESET), any());

        authService.forgotPassword(new ForgotPasswordRequest("9876543210"), "127.0.0.1");
        verify(otpService).issueAndSend(any(User.class), eq("9876543210"), eq(OtpPurpose.PASSWORD_RESET), eq("127.0.0.1"));
    }
}
