package com.jannetai.backend.dto.admin;

import java.time.LocalDateTime;

/**
 * One row of the Admin Settings screen (SRS 16.3 / 15.15). {@code value}
 * is null when {@code overridden} is false (no Admin has ever set this
 * key) - {@code recommendedDefault} is shown as a reference in that case;
 * the actual runtime default a consumer falls back to remains whatever
 * its own {@code @Value}-bound field resolves to (see
 * PlatformSettingsService's class Javadoc).
 */
public record SettingResponse(
        String key,
        String value,
        String recommendedDefault,
        boolean overridden,
        LocalDateTime updatedAt,
        String updatedByName
) {
}
