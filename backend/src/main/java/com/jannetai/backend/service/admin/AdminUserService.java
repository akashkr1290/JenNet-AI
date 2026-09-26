package com.jannetai.backend.service.admin;

import com.jannetai.backend.dto.admin.AdminCreateUserRequest;
import com.jannetai.backend.dto.admin.AdminCreateUserResponse;
import com.jannetai.backend.dto.auth.UserProfileResponse;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.exception.DuplicateAccountException;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.repository.WardRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 14 (Admin & Settings Module, SRS 15.11 "Admin Module" / 16.3
 * "User & Role Management" screen). Staff account provisioning was
 * explicitly deferred here from Phase 13's PHASE_HANDOFF.md carry-forward
 * note.
 *
 * ROLE-PRIVILEGE MATRIX (see {@link #requireManageableRole}):
 * <ul>
 *   <li>CITIZEN and SUPER_ADMIN accounts are never touchable through this
 *   endpoint. CITIZEN is self-registration only (AuthService#register);
 *   SUPER_ADMIN is bootstrap-only (SuperAdminBootstrap, Phase 4) - there
 *   is deliberately no path to create a second Super Admin through the
 *   application layer.</li>
 *   <li>SRS 15.11 Business Rules: "only Super Administrator may create or
 *   modify Admin accounts". Read literally as covering every mutating
 *   action here (create with role=ADMIN, role change to/from ADMIN,
 *   status change, password reset, session revocation on an existing
 *   ADMIN account) - an ordinary ADMIN actor can manage
 *   GOVERNMENT_OFFICER/DEPARTMENT_HEAD/VERIFICATION_TEAM/MAINTENANCE_TEAM
 *   accounts only.</li>
 * </ul>
 *
 * TEMPORARY PASSWORD DESIGN (see {@link #createStaff}): real SMS/email
 * delivery channels exist as of Phase 15 (EmailGatewayClient/
 * SmsGatewayClient), but this flow deliberately does not use them to
 * deliver the generated password - doing so would mean a temporary
 * credential travels over the same channel infrastructure used for
 * routine status alerts, and SRS 15.11/15.13 gives no instruction to
 * route staff-provisioning credentials through the Notification Module.
 * Rather than fabricate that integration speculatively, the generated
 * password is returned once, directly, in
 * {@link AdminCreateUserResponse} for the Admin to convey to the new
 * staff member out-of-band - documented as a known limitation, same
 * honesty convention as {@code SmsGatewayClient}'s disabled-channel
 * logging-stub fallback (Phase 15).
 * The account is marked mobile-verified immediately (Admin-provisioned
 * accounts are trusted, unlike self-registration's OTP verification
 * loop).
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final WardRepository wardRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final AuthService authService;

    @Transactional(readOnly = true)
    public Page<UserProfileResponse> list(Role role, UserStatus status, Long departmentId, String search,
                                           int page, int pageSize) {
        String normalizedSearch = (search != null && !search.isBlank()) ? search.trim() : null;
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(pageSize, 1), 100));
        return userRepository.searchAdminUsers(role, status, departmentId, normalizedSearch, pageable)
                .map(UserProfileResponse::from);
    }

    @Transactional
    public AdminCreateUserResponse createStaff(User actor, AdminCreateUserRequest request) {
        requireManageableRole(actor, request.role());

        if (userRepository.existsByMobileNumber(request.mobileNumber())) {
            throw new DuplicateAccountException("Mobile number is already registered");
        }
        if (request.email() != null && !request.email().isBlank()
                && userRepository.existsByEmail(request.email())) {
            throw new DuplicateAccountException("Email is already registered");
        }

        Department department = null;
        if (request.departmentId() != null) {
            department = departmentRepository.findById(request.departmentId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Department not found: " + request.departmentId()));
        }
        Ward ward = null;
        if (request.wardId() != null) {
            ward = wardRepository.findById(request.wardId())
                    .orElseThrow(() -> new ResourceNotFoundException("Ward not found: " + request.wardId()));
        }

        String temporaryPassword = generateTemporaryPassword();
        User user = User.builder()
                .fullName(request.fullName())
                .mobileNumber(request.mobileNumber())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .role(request.role())
                .department(department)
                .ward(ward)
                .reputationScore(100) // matches V3's DEFAULT 100, same as self-registration
                .status(UserStatus.ACTIVE)
                .failedLoginCount(0)
                .mobileVerifiedAt(LocalDateTime.now()) // Admin-provisioned - trusted, no OTP loop needed
                .build();
        User saved = userRepository.save(user);

        auditService.record(actor, "ADMIN_USER_CREATED", "USER", saved.getUserId(),
                "{\"role\":\"" + request.role() + "\"}");

        return new AdminCreateUserResponse(UserProfileResponse.from(saved), temporaryPassword);
    }

    @Transactional
    public UserProfileResponse updateRole(User actor, Long targetUserId, Role newRole) {
        User target = requireUser(targetUserId);
        requireManageableRole(actor, target.getRole()); // guards against touching an existing ADMIN account
        requireManageableRole(actor, newRole);           // guards against granting ADMIN without being SUPER_ADMIN

        Role before = target.getRole();
        target.setRole(newRole);
        User saved = userRepository.save(target);

        auditService.record(actor, "ADMIN_USER_ROLE_CHANGED", "USER", saved.getUserId(),
                "{\"before\":\"" + before + "\",\"after\":\"" + newRole + "\"}");

        return UserProfileResponse.from(saved);
    }

    @Transactional
    public UserProfileResponse updateStatus(User actor, Long targetUserId, UserStatus newStatus) {
        User target = requireUser(targetUserId);
        if (target.getUserId().equals(actor.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot change your own account status");
        }
        requireManageableRole(actor, target.getRole());
        if (target.getErasedAt() != null && newStatus == UserStatus.ACTIVE) {
            // Audit GAP-041: an erased account has no personal data left and must stay closed.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This account's personal data was erased; it cannot be reactivated");
        }

        UserStatus before = target.getStatus();
        target.setStatus(newStatus);
        User saved = userRepository.save(target);

        // A suspension should take effect immediately, not at the target's
        // current access token's natural expiry - same reasoning as
        // AuthService#resetPassword's "old password no longer trusted" call
        // to revokeAllForUser.
        if (newStatus == UserStatus.SUSPENDED) {
            authService.logoutAllSessions(saved.getUserId());
        }

        auditService.record(actor, "ADMIN_USER_STATUS_CHANGED", "USER", saved.getUserId(),
                "{\"before\":\"" + before + "\",\"after\":\"" + newStatus + "\"}");

        return UserProfileResponse.from(saved);
    }

    @Transactional
    public void triggerPasswordReset(User actor, Long targetUserId) {
        User target = requireUser(targetUserId);
        requireManageableRole(actor, target.getRole());
        authService.adminTriggerPasswordReset(actor, target);
    }

    @Transactional
    public void revokeSessions(User actor, Long targetUserId) {
        User target = requireUser(targetUserId);
        requireManageableRole(actor, target.getRole());
        authService.logoutAllSessions(target.getUserId());
        auditService.record(actor, "ADMIN_USER_SESSIONS_REVOKED", "USER", target.getUserId(), null);
    }

    // ---- helpers ----

    private User requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    /** See class Javadoc's "ROLE-PRIVILEGE MATRIX". */
    private void requireManageableRole(User actor, Role role) {
        if (role == Role.CITIZEN || role == Role.SUPER_ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "CITIZEN and SUPER_ADMIN accounts cannot be managed through this endpoint");
        }
        if (role == Role.ADMIN && actor.getRole() != Role.SUPER_ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only Super Administrator may create or modify Admin accounts");
        }
    }

    /**
     * Generates a random password satisfying RegisterRequest's own policy
     * pattern (min 8 chars, upper, lower, digit, special) - reused here
     * rather than duplicated so a staff account's temporary password is
     * held to exactly the same strength bar as a citizen's self-chosen
     * one. Ambiguous-looking characters (0/O, 1/l/I) are excluded so an
     * Admin reading it aloud/typing it out for a new staff member doesn't
     * introduce a transcription error.
     */
    private String generateTemporaryPassword() {
        SecureRandom random = new SecureRandom();
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String lower = "abcdefghijkmnpqrstuvwxyz";
        String digits = "23456789";
        String special = "!@#$%^&*";
        String all = upper + lower + digits + special;

        List<Character> chars = new ArrayList<>();
        chars.add(upper.charAt(random.nextInt(upper.length())));
        chars.add(lower.charAt(random.nextInt(lower.length())));
        chars.add(digits.charAt(random.nextInt(digits.length())));
        chars.add(special.charAt(random.nextInt(special.length())));
        for (int i = 0; i < 8; i++) {
            chars.add(all.charAt(random.nextInt(all.length())));
        }
        Collections.shuffle(chars, random);

        StringBuilder sb = new StringBuilder(chars.size());
        chars.forEach(sb::append);
        return sb.toString();
    }
}
