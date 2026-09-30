package com.jannetai.backend.dto.auth;

import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;

/**
 * Public-safe user profile shape - deliberately excludes passwordHash,
 * failedLoginCount, lockedUntil (internal security state never returned to
 * the client).
 *
 * departmentName / wardName (pilot 2026-09-30, so the app can show "which
 * department" a signed-in staff member belongs to) are only filled by
 * {@link #withNames(User)}, which must run inside a transaction because both
 * associations are lazy; {@link #from(User)} leaves them null.
 */
public record UserProfileResponse(
        Long userId,
        String fullName,
        String mobileNumber,
        String email,
        Role role,
        UserStatus status,
        Integer reputationScore,
        Long wardId,
        Long departmentId,
        String departmentName,
        String wardName
) {
    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
                user.getUserId(),
                user.getFullName(),
                user.getMobileNumber(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getReputationScore(),
                user.getWard() != null ? user.getWard().getWardId() : null,
                user.getDepartment() != null ? user.getDepartment().getDepartmentId() : null,
                null,
                null
        );
    }

    /** Same as {@link #from(User)} plus the department and ward names. Call inside a transaction. */
    public static UserProfileResponse withNames(User user) {
        UserProfileResponse base = from(user);
        return new UserProfileResponse(
                base.userId(), base.fullName(), base.mobileNumber(), base.email(), base.role(), base.status(),
                base.reputationScore(), base.wardId(), base.departmentId(),
                user.getDepartment() != null ? user.getDepartment().getName() : null,
                user.getWard() != null ? user.getWard().getName() : null);
    }
}
