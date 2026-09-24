package com.jannetai.backend.service.settings;

import com.jannetai.backend.entity.enums.Role;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 15 (Personal Settings, SRS 15.15 "Features": "citizen ...
 * profile settings", "officer availability/status settings", "language
 * and accessibility preferences"). Same closed-registry discipline as
 * {@code PlatformSettingKey}/{@code NotificationPreferenceKey} - all
 * USER-scoped (settings.scope = 'USER', scope_id = that user's user_id).
 *
 * SCOPE NOTE: "profile settings" itself (name/mobile/email/etc.) is
 * already covered by the Phase 5 {@code UpdateProfileRequest}/
 * {@code UserProfileService} - not duplicated here. This registry only
 * adds the settings that had nowhere to live before this phase: display
 * language, an accessibility toggle, and (officer-only) a self-reported
 * availability status.
 *
 * OFFICER_AVAILABILITY_STATUS is intentionally NOT wired into
 * {@code DepartmentAssignmentService#selectOfficer}'s eligibility query -
 * the SRS names this under "Personal Settings" (self-service/display),
 * not under 15.7's assignment business rules, and wiring it into the
 * completed Phase 11 auto-assignment logic without an explicit SRS
 * instruction to do so risks silently changing that phase's existing
 * behavior. Documented as a candidate follow-up in
 * PROJECT_INTEGRATION.md Section 6, not implemented speculatively.
 */
public enum PersonalSettingKey {

    /** SRS 15.15: "language ... preferences". Pilot scope is English/Hindi (SRS 15.1 Dependencies). */
    LANGUAGE("personal_language", Set.of("EN", "HI"), null),

    /** SRS 15.15: "accessibility preferences" - kept to a single boolean, no SRS detail beyond the phrase itself. */
    HIGH_CONTRAST_ENABLED("personal_high_contrast_enabled", Set.of("true", "false"), null),

    /** SRS 15.15: "officer availability/status settings" - self-reported only, see class Javadoc SCOPE NOTE. */
    OFFICER_AVAILABILITY_STATUS("officer_availability_status", Set.of("AVAILABLE", "BUSY", "ON_LEAVE"),
            Role.GOVERNMENT_OFFICER);

    private final String key;
    private final Set<String> allowedValues;
    private final Role restrictedToRole; // null = any authenticated role may set this key

    PersonalSettingKey(String key, Set<String> allowedValues, Role restrictedToRole) {
        this.key = key;
        this.allowedValues = allowedValues;
        this.restrictedToRole = restrictedToRole;
    }

    public String key() {
        return key;
    }

    public Role restrictedToRole() {
        return restrictedToRole;
    }

    public static Optional<PersonalSettingKey> fromKey(String key) {
        return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
    }

    /** @throws IllegalArgumentException if rawValue is not one of this key's allowed values. */
    public void validate(String rawValue) {
        if (rawValue == null || !allowedValues.contains(rawValue)) {
            throw new IllegalArgumentException(key + " must be one of " + allowedValues);
        }
    }
}
