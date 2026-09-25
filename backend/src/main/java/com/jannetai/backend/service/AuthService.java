package com.jannetai.backend.service;

import com.jannetai.backend.dto.auth.*;
import com.jannetai.backend.entity.OtpVerification;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.entity.enums.OtpChannel;
import com.jannetai.backend.entity.enums.OtpPurpose;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.exception.*;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.repository.WardRepository;
import com.jannetai.backend.security.JwtService;
import com.jannetai.backend.security.RefreshTokenService;
import com.jannetai.backend.security.RoleConstants;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Implements the Authentication Module (SRS 15.2). Business rule constants
 * (max failed attempts, lockout duration) are pinned here with the SRS
 * section they come from so a future rules-config feature (Settings
 * module, Phase 14) has a clear migration target.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;   // SRS 15.2
    private static final long LOCKOUT_MINUTES = 15;           // SRS 15.2

    private final UserRepository userRepository;
    private final WardRepository wardRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;

    @Transactional
    public UserProfileResponse register(RegisterRequest request, String requestIp) {
        if (userRepository.existsByMobileNumber(request.mobileNumber())) {
            throw new DuplicateAccountException("Mobile number is already registered");
        }
        if (request.email() != null && !request.email().isBlank()
                && userRepository.existsByEmail(request.email())) {
            throw new DuplicateAccountException("Email is already registered");
        }
        if (request.otpChannel() == OtpChannel.EMAIL && (request.email() == null || request.email().isBlank())) {
            throw new IllegalArgumentException("An e-mail address is required to receive the verification code by e-mail");
        }

        Ward ward = null;
        if (request.wardId() != null) {
            ward = wardRepository.findById(request.wardId())
                    .orElseThrow(() -> new ResourceNotFoundException("Ward not found: " + request.wardId()));
        }

        User user = User.builder()
                .fullName(request.fullName())
                .mobileNumber(request.mobileNumber())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(Role.CITIZEN) // self-registration is always CITIZEN; staff accounts are Admin-provisioned (Phase 14)
                .ward(ward)
                .reputationScore(100) // matches V3's DEFAULT 100
                .status(UserStatus.ACTIVE)
                .failedLoginCount(0)
                .build();
        User saved = userRepository.save(user);

        if (request.otpChannel() == OtpChannel.EMAIL) {
            otpService.issueAndSendByEmail(saved, OtpPurpose.REGISTRATION, requestIp); // audit GAP-004
        } else {
            otpService.issueAndSend(saved, saved.getMobileNumber(), OtpPurpose.REGISTRATION, requestIp);
        }
        auditService.record(saved, "REGISTER", saved.getUserId(), null);

        return UserProfileResponse.from(saved);
    }

    @Transactional(noRollbackFor = InvalidOtpException.class)
    public void verifyRegistrationOtp(VerifyOtpRequest request) {
        OtpVerification otp = otpService.verifyAndConsume(
                request.mobileNumber(), OtpPurpose.REGISTRATION, request.otpCode());

        User user = otp.getUser() != null ? otp.getUser()
                : userRepository.findByMobileNumber(request.mobileNumber())
                        .orElseThrow(() -> new ResourceNotFoundException("User not found for this mobile number"));
        // Audit GAP-004: the code proves control of whichever identifier it was sent to.
        if (otp.getChannel() == OtpChannel.EMAIL) {
            user.setEmailVerifiedAt(LocalDateTime.now());
            userRepository.save(user);
            auditService.record(user, "EMAIL_VERIFIED", user.getUserId(), null);
        } else {
            user.setMobileVerifiedAt(LocalDateTime.now());
            userRepository.save(user);
            auditService.record(user, "MOBILE_VERIFIED", user.getUserId(), null);
        }
    }

    @Transactional
    public void resendOtp(ResendOtpRequest request, String requestIp) {
        User user = userRepository.findByMobileNumber(request.mobileNumber()).orElse(null);
        // Audit GAP-049: never reveal whether a mobile number is registered.
        // An unknown number gets the same generic "OTP resent if the account
        // exists" response for every purpose (previously REGISTRATION and
        // LOGIN_MFA returned 404 "No account found", which enumerated accounts).
        if (user == null) {
            return;
        }
        if (request.purpose() == OtpPurpose.PASSWORD_RESET) {
            issuePasswordResetOtpWithoutRevealingAccount(user, request.mobileNumber(), requestIp, request.channel());
        } else if (request.purpose() == OtpPurpose.REGISTRATION && request.channel() == OtpChannel.EMAIL) {
            // Audit GAP-004: re-send the registration code by e-mail. An account
            // without an address gets the same generic response as an unknown
            // number (GAP-049), so this path cannot be used to probe accounts.
            if (user.getEmail() != null && !user.getEmail().isBlank()) {
                otpService.issueAndSendByEmail(user, OtpPurpose.REGISTRATION, requestIp);
            }
        } else {
            // Registration OTP fix: a delivery failure now surfaces as
            // 503 OTP_DELIVERY_FAILED instead of "OTP resent" for a code
            // that was never sent. LOGIN_MFA is always SMS.
            otpService.issueAndSend(user, request.mobileNumber(), request.purpose(), requestIp);
        }
    }

    /**
     * Registration OTP fix: the password-reset OTP paths answer identically
     * whether or not the account exists (anti-enumeration). A delivery failure
     * can only happen for an existing account, so letting it surface as 503
     * would reveal which numbers are registered. It is therefore logged
     * server-side (OTP_DELIVERY_FAILED, by the delivery service) and the
     * generic response is kept.
     */
    private void issuePasswordResetOtpWithoutRevealingAccount(User user, String mobileNumber, String requestIp,
                                                              OtpChannel channel) {
        try {
            if (channel == OtpChannel.EMAIL) {
                // Audit GAP-004. An account without an e-mail address is treated
                // like any other delivery failure: nothing is sent and nothing is
                // reported (anti-enumeration). Checked here, before OtpService, so
                // no exception marks the joined transaction rollback-only.
                if (user.getEmail() != null && !user.getEmail().isBlank()) {
                    otpService.issueAndSendByEmail(user, OtpPurpose.PASSWORD_RESET, requestIp);
                }
            } else {
                otpService.issueAndSend(user, mobileNumber, OtpPurpose.PASSWORD_RESET, requestIp);
            }
        } catch (OtpDeliveryException e) {
            // Intentionally not rethrown - see method Javadoc.
        }
    }

    /**
     * Returns either an AuthResponse (tokens issued) or throws
     * MfaRequiredException (Admin/Super Admin - caller must complete
     * /mfa/verify). Never returns null.
     *
     * noRollbackFor is required for both exceptions below: each is thrown
     * deliberately mid-method AFTER a DB write this method needs to
     * survive - MfaRequiredException after the MFA OTP row is persisted,
     * BadCredentialsExceptionWrapper after registerFailedAttempt() persists
     * the incremented failed-login counter / lockout timestamp. Without
     * this, Spring's default rollback-on-RuntimeException would silently
     * discard both, breaking the lockout feature and stranding the client
     * in an MFA state with no OTP actually saved.
     */
    @Transactional(noRollbackFor = {MfaRequiredException.class, BadCredentialsExceptionWrapper.class})
    public AuthResponse login(LoginRequest request, String deviceLabel, String ipAddress) {
        User user = userRepository.findByMobileNumberOrEmail(request.identifier(), request.identifier())
                .orElseThrow(() -> new BadCredentialsExceptionWrapper());

        enforceNotLockedOrSuspended(user);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            registerFailedAttempt(user);
            throw new BadCredentialsExceptionWrapper();
        }

        // Successful password check resets the failure counter regardless of
        // whether MFA is still pending (MFA failure is tracked separately via
        // the OTP's own attempt_count, not the account lockout counter).
        resetFailedAttempts(user);

        if (RoleConstants.requiresMfa(user.getRole())) {
            otpService.issueAndSend(user, user.getMobileNumber(), OtpPurpose.LOGIN_MFA, ipAddress);
            auditService.record(user, "LOGIN_MFA_ISSUED", user.getUserId(), null);
            throw new MfaRequiredException(jwtService.generateMfaToken(user));
        }

        auditService.record(user, "LOGIN_SUCCESS", user.getUserId(), null);
        return issueTokens(user, deviceLabel, ipAddress);
    }

    @Transactional(noRollbackFor = InvalidOtpException.class)
    public AuthResponse verifyMfa(MfaVerifyRequest request, String deviceLabel, String ipAddress) {
        Long userId;
        try {
            userId = jwtService.validateMfaTokenAndGetUserId(request.mfaToken());
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidRefreshTokenException("MFA session expired or invalid; please log in again");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        enforceNotLockedOrSuspended(user);

        otpService.verifyAndConsume(user.getMobileNumber(), OtpPurpose.LOGIN_MFA, request.otpCode());

        auditService.record(user, "LOGIN_SUCCESS_MFA", user.getUserId(), null);
        return issueTokens(user, deviceLabel, ipAddress);
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public AuthResponse refresh(RefreshRequest request, String deviceLabel, String ipAddress) {
        RefreshTokenService.IssuedToken rotated =
                refreshTokenService.validateAndRotate(request.refreshToken(), deviceLabel, ipAddress);
        User user = rotated.entity().getUser();
        enforceNotLockedOrSuspended(user);
        return new AuthResponse(
                jwtService.generateAccessToken(user),
                rotated.rawToken(),
                jwtService.accessTokenExpiryMinutes() * 60,
                UserProfileResponse.from(user)
        );
    }

    @Transactional
    public void logout(LogoutRequest request) {
        refreshTokenService.revokeFamilyOf(request.refreshToken());
    }

    @Transactional
    public void logoutAllSessions(Long userId) {
        refreshTokenService.revokeAllForUser(userId);
    }

    /**
     * Phase 14 (Admin & Settings Module, SRS 16.3 "Reset Password" staff
     * action / Security 27.5). Reuses the existing self-service OTP-based
     * reset flow rather than inventing an Admin-only path: an Admin can
     * only trigger the same PASSWORD_RESET OTP {@link #forgotPassword}
     * issues for a self-service request - the target user still has to
     * complete it themselves via POST /auth/reset-password with the OTP
     * they receive. This keeps a single password-reset code path and
     * avoids ever having an Admin see or set a user's actual password.
     */
    @Transactional
    public void adminTriggerPasswordReset(User admin, User target) {
        otpService.issueAndSend(target, target.getMobileNumber(), OtpPurpose.PASSWORD_RESET, null);
        auditService.record(admin, "ADMIN_PASSWORD_RESET_TRIGGERED", "USER", target.getUserId(), null);
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request, String requestIp) {
        userRepository.findByMobileNumber(request.mobileNumber())
                .ifPresent(user -> issuePasswordResetOtpWithoutRevealingAccount(
                        user, request.mobileNumber(), requestIp, request.channel()));
        // Always returns success-shaped response regardless of whether the
        // account exists (AuthController), to avoid account enumeration.
    }

    @Transactional(noRollbackFor = InvalidOtpException.class)
    public void resetPassword(ResetPasswordRequest request) {
        OtpVerification otp = otpService.verifyAndConsume(
                request.mobileNumber(), OtpPurpose.PASSWORD_RESET, request.otpCode());
        User user = otp.getUser() != null ? otp.getUser()
                : userRepository.findByMobileNumber(request.mobileNumber())
                        .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        // A successful password reset invalidates every existing session -
        // the old password is no longer trusted to have been the only way in.
        refreshTokenService.revokeAllForUser(user.getUserId());
        auditService.record(user, "PASSWORD_RESET", user.getUserId(), null);
    }

    // ---- helpers ----

    private AuthResponse issueTokens(User user, String deviceLabel, String ipAddress) {
        RefreshTokenService.IssuedToken issued = refreshTokenService.issueNewFamily(user, deviceLabel, ipAddress);
        return new AuthResponse(
                jwtService.generateAccessToken(user),
                issued.rawToken(),
                jwtService.accessTokenExpiryMinutes() * 60,
                UserProfileResponse.from(user)
        );
    }

    private void enforceNotLockedOrSuspended(User user) {
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new AccountSuspendedException("This account has been suspended");
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new AccountLockedException(
                    "Account is locked due to too many failed login attempts; try again later");
        }
    }

    private void registerFailedAttempt(User user) {
        int attempts = user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount();
        attempts += 1;
        if (attempts >= MAX_FAILED_LOGIN_ATTEMPTS) {
            user.setFailedLoginCount(0);
            user.setLockedUntil(LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES));
            auditService.record(user, "ACCOUNT_LOCKED", user.getUserId(), null);
        } else {
            user.setFailedLoginCount(attempts);
        }
        userRepository.save(user);
        auditService.record(user, "LOGIN_FAILED", user.getUserId(), null);
    }

    private void resetFailedAttempts(User user) {
        if ((user.getFailedLoginCount() != null && user.getFailedLoginCount() > 0) || user.getLockedUntil() != null) {
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }
    }

    /** Internal marker so login()'s "not found" and "wrong password" paths share one exception type/message. */
    private static class BadCredentialsExceptionWrapper
            extends org.springframework.security.authentication.BadCredentialsException {
        BadCredentialsExceptionWrapper() {
            super("Invalid credentials");
        }
    }
}
