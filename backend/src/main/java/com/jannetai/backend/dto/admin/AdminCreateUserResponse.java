package com.jannetai.backend.dto.admin;

import com.jannetai.backend.dto.auth.UserProfileResponse;

/**
 * POST /api/v1/admin/users response. The generated temporary password is
 * deliberately NOT part of it: it is e-mailed to the new staff member's own
 * address (see AdminUserService's Javadoc, "TEMPORARY PASSWORD DESIGN") and
 * only its BCrypt hash is stored. {@code passwordDeliveredTo} is that address,
 * masked (e.g. {@code a***@gmail.com}), so the Admin can see where it went.
 */
public record AdminCreateUserResponse(
        UserProfileResponse user,
        String passwordDeliveredTo
) {
}
