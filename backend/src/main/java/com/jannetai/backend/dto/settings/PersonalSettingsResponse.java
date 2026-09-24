package com.jannetai.backend.dto.settings;

import com.jannetai.backend.dto.notification.NotificationPreferencesResponse;

/**
 * GET /api/v1/users/me/settings (Phase 15, SRS 15.15). Bundles the
 * language/accessibility/officer-availability keys owned by
 * {@code PersonalSettingsService} together with the notification
 * preferences owned by {@code NotificationService}, so the Flutter
 * Settings screen (a single screen per SRS 16.x's per-role screen list)
 * can render everything from one call. {@code officerAvailabilityStatus}
 * is null for any non-GOVERNMENT_OFFICER caller (see
 * {@code PersonalSettingKey#restrictedToRole()}).
 */
public record PersonalSettingsResponse(
        String language,
        boolean highContrastEnabled,
        String officerAvailabilityStatus,
        NotificationPreferencesResponse notificationPreferences
) {
}
