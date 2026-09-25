package com.jannetai.backend.dto.admin;

import java.time.LocalDateTime;

/**
 * Audit GAP-037 (SRS 15.11 "platform-wide announcement and maintenance-mode
 * control"): what every client shows as a banner. Public (no token), so it
 * carries only the texts the Admin wrote for everyone to read.
 */
public record PlatformStatusResponse(
        boolean maintenanceMode,
        String maintenanceMessage,
        int retryAfterSeconds,
        String announcement,
        LocalDateTime updatedAt
) {
}
