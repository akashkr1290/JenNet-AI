package com.jannetai.backend.dto.admin;

import com.jannetai.backend.entity.enums.UserStatus;
import jakarta.validation.constraints.NotNull;

/** PATCH /api/v1/admin/users/{id}/status body (SRS 16.3 activate/suspend action). */
public record AdminUpdateStatusRequest(
        @NotNull
        UserStatus status
) {
}
