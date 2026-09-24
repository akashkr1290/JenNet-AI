package com.jannetai.backend.dto.notification;

/**
 * SRS 20.5 GET/PUT /api/v1/notifications/preferences. Note: per
 * {@code NotificationPreferenceKey}'s Javadoc, {@code emailEnabled} is
 * stored and returned here but is NOT actually consulted before sending
 * a major status-change/officer-assignment email - those are mandatory
 * regardless of this value (SRS 15.13 Exceptions).
 */
public record NotificationPreferencesResponse(
        boolean smsEnabled,
        boolean pushEnabled,
        boolean emailEnabled
) {
}
