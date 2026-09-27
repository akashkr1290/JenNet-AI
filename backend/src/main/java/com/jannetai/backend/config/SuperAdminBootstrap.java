package com.jannetai.backend.config;

import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Resolves the blocker Phase 3 flagged in PHASE_HANDOFF.md /
 * PROJECT_PROGRESS.md ("no user account exists yet anywhere in this
 * schema ... Phase 4 is expected to create the first account(s)"), which
 * also unblocks routing_rules/settings created_by/updated_by seeding
 * (Phase 2's KNOWN LIMITATIONS - still owned by whichever phase populates
 * those rows, not this one).
 *
 * Runs once at startup: if BOOTSTRAP_SUPER_ADMIN_MOBILE is set and no user
 * with that mobile number exists yet, creates one SUPER_ADMIN account with
 * BOOTSTRAP_SUPER_ADMIN_PASSWORD (bcrypt-hashed, never logged). Idempotent
 * - safe to leave the env vars set across restarts. If the mobile var is
 * unset, this is a silent no-op (e.g. CI/test contexts that don't need it).
 */
@Component
@org.springframework.core.annotation.Order(0) // audit GAP-036: before RoutingRuleBootstrap, which needs this account
@RequiredArgsConstructor
@Slf4j
public class SuperAdminBootstrap implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.super-admin.mobile-number:}")
    private String mobileNumber;

    @Value("${app.bootstrap.super-admin.password:}")
    private String password;

    @Value("${app.bootstrap.super-admin.full-name:System Administrator}")
    private String fullName;

    /**
     * Optional (BOOTSTRAP_SUPER_ADMIN_EMAIL). Lets the Super Admin receive the
     * login MFA code by e-mail when the pilot e-mail fallback is on
     * (AuthService.mfaEmailFallback). Set on a new account, and filled in on an
     * existing bootstrap account only while that account has no e-mail yet and
     * no other account uses the address - never overwrites a real value.
     */
    @Value("${app.bootstrap.super-admin.email:}")
    private String email;

    @Override
    @Transactional
    public void run(String... args) {
        if (mobileNumber == null || mobileNumber.isBlank()) {
            return;
        }
        if (userRepository.existsByMobileNumber(mobileNumber)) {
            backfillEmailIfMissing();
            return;
        }
        if (password == null || password.isBlank()) {
            log.warn("BOOTSTRAP_SUPER_ADMIN_MOBILE is set but BOOTSTRAP_SUPER_ADMIN_PASSWORD is not - " +
                    "skipping Super Admin bootstrap.");
            return;
        }

        User superAdmin = User.builder()
                .fullName(fullName)
                .mobileNumber(mobileNumber)
                .passwordHash(passwordEncoder.encode(password))
                .role(Role.SUPER_ADMIN)
                .reputationScore(100)
                .status(UserStatus.ACTIVE)
                .failedLoginCount(0)
                .mobileVerifiedAt(LocalDateTime.now())
                .build();
        if (hasEmail() && !userRepository.existsByEmail(email.trim())) {
            superAdmin.setEmail(email.trim());
            superAdmin.setEmailVerifiedAt(LocalDateTime.now());
        }
        userRepository.save(superAdmin);
        log.info("Bootstrapped initial SUPER_ADMIN account for mobile number {}",
                com.jannetai.backend.service.notification.PiiMask.phone(mobileNumber)); // item 15: masked
    }

    private void backfillEmailIfMissing() {
        if (!hasEmail()) {
            return;
        }
        userRepository.findByMobileNumber(mobileNumber).ifPresent(user -> {
            if (user.getEmail() != null && !user.getEmail().isBlank()) {
                return; // never overwrite an existing address
            }
            if (userRepository.existsByEmail(email.trim())) {
                log.warn("BOOTSTRAP_SUPER_ADMIN_EMAIL is already used by another account - not added to the Super Admin");
                return;
            }
            user.setEmail(email.trim());
            user.setEmailVerifiedAt(LocalDateTime.now());
            userRepository.save(user);
            log.info("Added BOOTSTRAP_SUPER_ADMIN_EMAIL {} to the existing Super Admin account",
                    com.jannetai.backend.service.notification.PiiMask.email(email.trim()));
        });
    }

    private boolean hasEmail() {
        return email != null && !email.isBlank();
    }
}
