package com.jannetai.backend.dto.admin;

import com.jannetai.backend.entity.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/admin/users body (SRS 16.3 Admin "Add User" screen; 15.11
 * Admin Module). Field constraints mirror {@link com.jannetai.backend.dto.auth.RegisterRequest}
 * (same mobile-number/email rules) - a staff account is still a
 * {@code users} row and must satisfy the same schema constraints as a
 * citizen self-registration. No password field: {@link com.jannetai.backend.service.admin.AdminUserService}
 * generates a temporary password (see its Javadoc for why - real SMS/
 * email delivery channels exist as of Phase 15, but this Admin-provisioning
 * flow was deliberately left as-is rather than retrofitted onto them).
 */
public record AdminCreateUserRequest(

        @NotBlank @Size(max = 100)
        String fullName,

        @NotBlank @Pattern(regexp = "^[6-9]\\d{9}$", message = "must be a valid 10-digit mobile number")
        String mobileNumber,

        @Email @Size(max = 150)
        String email,

        /**
         * Restricted to non-CITIZEN, non-SUPER_ADMIN roles at the service
         * layer (AdminUserService#requireManageableRole) - CITIZEN accounts
         * are self-registration only, and SUPER_ADMIN is bootstrap-only
         * (SuperAdminBootstrap, Phase 4). Assigning role=ADMIN additionally
         * requires the acting user to already be SUPER_ADMIN (SRS 15.11:
         * "only Super Administrator may create or modify Admin accounts").
         */
        @NotNull
        Role role,

        Long departmentId,

        Long wardId
) {
}
