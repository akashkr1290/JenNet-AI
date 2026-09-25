package com.jannetai.backend.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Audit GAP-037: Admin update of maintenance mode and the announcement banner.
 * Texts are limited to 200 characters (settings.value column). A blank
 * announcement removes the banner. {@code retryAfterMinutes} is what write
 * requests are told (HTTP Retry-After) while maintenance mode is on.
 */
public record PlatformStatusUpdateRequest(
        @NotNull Boolean maintenanceMode,
        @Size(max = 200) String maintenanceMessage,
        @Min(1) @Max(1440) Integer retryAfterMinutes,
        @Size(max = 200) String announcement
) {
}
