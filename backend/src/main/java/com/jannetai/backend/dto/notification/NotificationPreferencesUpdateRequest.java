package com.jannetai.backend.dto.notification;

/**
 * SRS 20.5 PUT /api/v1/notifications/preferences. All fields optional
 * (partial update) - a null field leaves that preference unchanged, so a
 * client can toggle just one switch without re-sending the other two.
 */
public record NotificationPreferencesUpdateRequest(
        Boolean smsEnabled,
        Boolean pushEnabled,
        Boolean emailEnabled
) {
}
