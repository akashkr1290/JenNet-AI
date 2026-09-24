package com.jannetai.backend.service.notification;

import java.util.Arrays;
import java.util.Optional;

/**
 * Phase 15 (Notification Module, SRS 15.13/20.5 {@code PUT
 * /api/v1/notifications/preferences} - body {@code sms_enabled},
 * {@code push_enabled}, {@code email_enabled}). Same closed-registry
 * discipline as {@code PlatformSettingKey} (Phase 14): each preference
 * key is USER-scoped (settings.scope = 'USER', scope_id = that user's
 * user_id), boolean-valued ("true"/"false" strings), defaulting to
 * {@code true} when no row exists yet (an existing citizen who never
 * visits the settings screen is opted in to everything by default,
 * matching SRS 15.13's framing of these as opt-OUT toggles).
 *
 * NOTE on EMAIL_ENABLED (SRS 15.13 Exceptions vs 20.5 API table
 * conflict, documented in PROJECT_INTEGRATION.md Section 6): the
 * Exceptions text says "citizens who opt out of SMS/push still receive
 * mandatory in-app and email notifications", i.e. email cannot actually
 * be turned off for complaint status-change alerts - but the API table
 * still names an {@code email_enabled} field. This key is kept (so the
 * documented API shape is honored and a future non-status-change email
 * type, e.g. a digest, has somewhere to read a real preference from) but
 * {@link NotificationService#notifyComplaintStatusChanged} and the other
 * trigger methods do NOT consult it - they always send EMAIL for major
 * status changes/officer alerts regardless of this value, per the
 * Exceptions rule winning over the API table for that specific behavior.
 */
public enum NotificationPreferenceKey {

    SMS_ENABLED("notification_sms_enabled"),
    PUSH_ENABLED("notification_push_enabled"),
    EMAIL_ENABLED("notification_email_enabled");

    private final String key;

    NotificationPreferenceKey(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<NotificationPreferenceKey> fromKey(String key) {
        return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
    }
}
