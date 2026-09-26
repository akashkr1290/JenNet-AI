package com.jannetai.backend.repository;

import com.jannetai.backend.entity.OtpVerification;
import com.jannetai.backend.entity.enums.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Query methods here are intentionally narrow (only what AuthService/
 * OtpService need) rather than exposed as generic CRUD, since OTP rows are
 * security-sensitive.
 */
@Repository
public interface OtpVerificationRepository extends JpaRepository<OtpVerification, Long> {

    /**
     * Most recent still-usable (unconsumed) OTP for a given mobile number
     * and purpose. The service layer additionally checks expiry and
     * attempt-count before accepting it.
     */
    Optional<OtpVerification> findFirstByMobileNumberAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            String mobileNumber, OtpPurpose purpose);

    /** Audit GAP-041: personal-data erasure (codes are hashed, the rows still hold the mobile number). */
    void deleteByUser_UserId(Long userId);

    void deleteByMobileNumber(String mobileNumber);
}
