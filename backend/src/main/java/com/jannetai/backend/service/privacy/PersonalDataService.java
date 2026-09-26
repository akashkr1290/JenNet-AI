package com.jannetai.backend.service.privacy;

import com.jannetai.backend.dto.privacy.PersonalDataExport;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Setting;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.SettingScope;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.ComplaintAppealRepository;
import com.jannetai.backend.repository.ComplaintRatingRepository;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.DeviceTokenRepository;
import com.jannetai.backend.repository.OtpVerificationRepository;
import com.jannetai.backend.repository.RefreshTokenRepository;
import com.jannetai.backend.repository.SettingRepository;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.service.AuditJson;
import com.jannetai.backend.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Audit GAP-041 (SRS 24 Compliance: "citizen data access/erasure request
 * handling"; SRS 27.2).
 *
 * <p><b>Access</b> ({@link #export}): the user's own profile, personal
 * settings, complaints, appeals and ratings as one JSON document.
 *
 * <p><b>Erasure</b> ({@link #erase}): the account row is kept, because
 * complaints, status history and audit logs reference it and are civic
 * records, but everything that identifies the person is removed:
 * <ul>
 *   <li>name -> "Erased user", mobile -> an impossible placeholder
 *       ("ERASED" + id, never a valid Indian mobile), e-mail -> null,
 *       password -> random hash, verification timestamps cleared,
 *       status SUSPENDED, {@code erased_at} set (the account can never be
 *       reactivated - AdminUserService refuses);</li>
 *   <li>all sessions (refresh tokens) revoked, push device tokens, OTP rows
 *       and personal settings deleted.</li>
 * </ul>
 * Complaint descriptions, photos and locations are kept: they describe a
 * public place, not the person, and are needed for the civic record. Whether
 * they must also be removed or redacted is a product-owner / legal decision
 * (docs/PRODUCTION_HARDENING.md). Audit logs are append-only by SRS 27.4 and
 * already mask personal data.
 */
@Service
@RequiredArgsConstructor
public class PersonalDataService {

    static final String ERASED_NAME = "Erased user";

    private final UserRepository userRepository;
    private final ComplaintRepository complaintRepository;
    private final ComplaintAppealRepository complaintAppealRepository;
    private final ComplaintRatingRepository complaintRatingRepository;
    private final SettingRepository settingRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final OtpVerificationRepository otpVerificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    @Transactional(readOnly = true)
    public PersonalDataExport export(User requester) {
        User user = userRepository.findById(requester.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + requester.getUserId()));
        Map<String, String> settings = new LinkedHashMap<>();
        for (Setting s : settingRepository.findByScopeAndScopeId(SettingScope.USER, user.getUserId())) {
            settings.put(s.getKey(), s.getValue());
        }
        var profile = new PersonalDataExport.Profile(user.getUserId(), user.getFullName(), user.getMobileNumber(),
                user.getEmail(), user.getRole().name(), user.getStatus().name(),
                user.getWard() != null ? user.getWard().getWardId() : null,
                user.getWard() != null ? user.getWard().getName() : null,
                user.getReputationScore(), user.getMobileVerifiedAt(), user.getEmailVerifiedAt(), user.getCreatedAt());
        var complaints = complaintRepository.findByCitizen_UserIdOrderByCreatedAtDesc(user.getUserId()).stream()
                .map(PersonalDataService::complaintItem).toList();
        var appeals = complaintAppealRepository.findByCitizen_UserIdOrderByCreatedAtDesc(user.getUserId()).stream()
                .map(a -> new PersonalDataExport.AppealItem(a.getAppealId(), a.getComplaint().getComplaintId(),
                        a.getReason(), a.getStatus() != null ? a.getStatus().name() : null, a.getCreatedAt()))
                .toList();
        var ratings = complaintRatingRepository.findByCitizen_UserIdOrderByCreatedAtDesc(user.getUserId()).stream()
                .map(r -> new PersonalDataExport.RatingItem(r.getComplaint().getComplaintId(), r.getRating(),
                        r.getComment(), r.getCreatedAt()))
                .toList();
        auditService.record(user, "PERSONAL_DATA_EXPORTED", "USER", user.getUserId(), null);
        return new PersonalDataExport(LocalDateTime.now(), profile, settings, complaints, appeals, ratings);
    }

    /** Self-service erasure: CITIZEN accounts only, confirmed with the current password. */
    @Transactional
    public void eraseOwnAccount(User requester, String password) {
        User user = userRepository.findById(requester.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + requester.getUserId()));
        if (user.getRole() != Role.CITIZEN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Staff accounts are erased by a Super Administrator, not self-service");
        }
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Password is incorrect");
        }
        erase(user, user, "self-service");
    }

    /** SUPER_ADMIN erasure on behalf of a person whose request arrived outside the app. */
    @Transactional
    public void eraseOnBehalf(User superAdmin, Long targetUserId, String reference) {
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + targetUserId));
        if (target.getUserId().equals(superAdmin.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot erase your own account this way");
        }
        if (target.getRole() == Role.SUPER_ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A Super Administrator account cannot be erased here");
        }
        erase(superAdmin, target, reference);
    }

    private void erase(User actor, User user, String reference) {
        if (user.getErasedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This account has already been erased");
        }
        LocalDateTime now = LocalDateTime.now();
        String previousMobile = user.getMobileNumber();

        refreshTokenRepository.revokeAllForUser(user.getUserId(), now);
        deviceTokenRepository.deleteByUser_UserId(user.getUserId());
        otpVerificationRepository.deleteByUser_UserId(user.getUserId());
        if (previousMobile != null) {
            otpVerificationRepository.deleteByMobileNumber(previousMobile);
        }
        settingRepository.deleteByScopeAndScopeId(SettingScope.USER, user.getUserId());

        user.setFullName(ERASED_NAME);
        user.setMobileNumber(placeholderMobile(user.getUserId()));
        user.setEmail(null);
        user.setPasswordHash(passwordEncoder.encode(randomSecret()));
        user.setMobileVerifiedAt(null);
        user.setEmailVerifiedAt(null);
        user.setStatus(UserStatus.SUSPENDED);
        user.setErasedAt(now);
        userRepository.save(user);

        // Only identifiers are logged (SRS 27): never the erased values.
        auditService.record(actor, "USER_PERSONAL_DATA_ERASED", "USER", user.getUserId(),
                AuditJson.of("reference", reference, "self_service", actor.getUserId().equals(user.getUserId())));
    }

    /** "ERASED" + id: unique, at most 15 characters for ids below 10^9, and never a valid mobile number. */
    static String placeholderMobile(Long userId) {
        return "ERASED" + userId;
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static PersonalDataExport.ComplaintItem complaintItem(Complaint c) {
        var loc = c.getLocation();
        return new PersonalDataExport.ComplaintItem(c.getComplaintId(), c.getReferenceNumber(),
                c.getCategory() != null ? c.getCategory().name() : null,
                c.getStatus() != null ? c.getStatus().name() : null, c.getDescription(),
                loc != null ? loc.getLatitude() : null, loc != null ? loc.getLongitude() : null,
                loc != null ? loc.getFormattedAddress() : null, c.getCreatedAt());
    }
}
