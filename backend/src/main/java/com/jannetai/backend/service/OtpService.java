package com.jannetai.backend.service;

import com.jannetai.backend.entity.OtpVerification;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.OtpPurpose;
import com.jannetai.backend.exception.InvalidOtpException;
import com.jannetai.backend.exception.OtpDeliveryException;
import com.jannetai.backend.repository.OtpVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * SRS 15.2 Validation Rules: "OTP validity window of 5 minutes". Business
 * Rules don't specify a max-attempt count for guessing a single OTP; 5 is
 * used here as a documented, conservative default consistent with the
 * account-lockout threshold elsewhere in the same section.
 */
@Service
@RequiredArgsConstructor
public class OtpService {

    private static final int OTP_LENGTH = 6;
    private static final long VALIDITY_MINUTES = 5;
    private static final int MAX_ATTEMPTS = 5;

    private final OtpVerificationRepository otpVerificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpDeliveryService otpDeliveryService;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Registration OTP fix: noRollbackFor OtpDeliveryException so a delivery
     * failure does not mark a caller's joined transaction rollback-only here.
     * The exception still propagates. Registration/MFA/resend callers let it
     * roll back their own transaction (default rule for a RuntimeException),
     * while the enumeration-safe password-reset paths in AuthService catch it
     * and commit normally, keeping their response identical whether or not
     * the account exists.
     */
    @Transactional(noRollbackFor = OtpDeliveryException.class)
    public void issueAndSend(User user, String mobileNumber, OtpPurpose purpose, String requestIp) {
        String otpCode = generateCode();
        OtpVerification otp = OtpVerification.builder()
                .user(user)
                .mobileNumber(mobileNumber)
                .purpose(purpose)
                .otpCodeHash(passwordEncoder.encode(otpCode))
                .attemptCount(0)
                .maxAttempts(MAX_ATTEMPTS)
                .expiresAt(LocalDateTime.now().plusMinutes(VALIDITY_MINUTES))
                .requestIp(requestIp)
                .build();
        otpVerificationRepository.save(otp);
        otpDeliveryService.sendOtp(mobileNumber, otpCode, purpose.name());
    }

    /**
     * Validates and consumes the most recent unconsumed OTP for this
     * mobile/purpose. Throws InvalidOtpException on any failure (no OTP
     * found, expired, attempts exhausted, or code mismatch) - the caller
     * never needs to distinguish these cases from the client's perspective
     * (SRS 15.2: generic "400 Invalid/expired OTP").
     *
     * noRollbackFor is required: on a wrong code, attempt_count is
     * incremented and saved BEFORE this method throws - without
     * noRollbackFor, Spring's default rollback-on-RuntimeException would
     * discard that increment (whether this method is the transaction
     * boundary itself, or is joining an outer @Transactional caller such
     * as AuthService.verifyMfa - rollback rules are evaluated per
     * @Transactional method regardless of nesting), silently defeating the
     * max-attempts guard.
     */
    @Transactional(noRollbackFor = InvalidOtpException.class)
    public OtpVerification verifyAndConsume(String mobileNumber, OtpPurpose purpose, String otpCode) {
        OtpVerification otp = otpVerificationRepository
                .findFirstByMobileNumberAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(mobileNumber, purpose)
                .orElseThrow(() -> new InvalidOtpException("No pending OTP for this request"));

        if (otp.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidOtpException("OTP has expired");
        }
        if (otp.getAttemptCount() >= otp.getMaxAttempts()) {
            throw new InvalidOtpException("Maximum OTP attempts exceeded; request a new code");
        }

        boolean matches = passwordEncoder.matches(otpCode, otp.getOtpCodeHash());
        otp.setAttemptCount(otp.getAttemptCount() + 1);
        if (!matches) {
            otpVerificationRepository.save(otp);
            throw new InvalidOtpException("Incorrect OTP code");
        }

        otp.setConsumedAt(LocalDateTime.now());
        otpVerificationRepository.save(otp);
        return otp;
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, OTP_LENGTH);
        int value = secureRandom.nextInt(bound);
        return String.format("%0" + OTP_LENGTH + "d", value);
    }
}
