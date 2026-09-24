package com.jannetai.backend.dto.admin;

import com.jannetai.backend.entity.enums.Role;
import jakarta.validation.constraints.NotNull;

/** PATCH /api/v1/admin/users/{id}/role body (SRS 16.3 "User & Role Management"). */
public record AdminUpdateRoleRequest(
        @NotNull
        Role role
) {
}
