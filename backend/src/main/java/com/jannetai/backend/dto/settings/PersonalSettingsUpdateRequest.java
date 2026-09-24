package com.jannetai.backend.dto.settings;

/**
 * PATCH /api/v1/users/me/settings (Phase 15, SRS 15.15). Partial update -
 * a null field leaves that setting unchanged. {@code officerAvailabilityStatus}
 * is rejected with 403 for any caller who is not a GOVERNMENT_OFFICER (see
 * {@code PersonalSettingsService#updateSettings}).
 */
public record PersonalSettingsUpdateRequest(
        String language,
        Boolean highContrastEnabled,
        String officerAvailabilityStatus
) {
}
