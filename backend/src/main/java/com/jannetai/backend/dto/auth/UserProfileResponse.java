package com.jannetai.backend.dto.auth;

import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;

/**
 * Public-safe user profile shape - deliberately excludes passwordHash,
 * failedLoginCount, lockedUntil (internal security state never returned to
 * the client).
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
        Long departmentId
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
                user.getDepartment() != null ? user.getDepartment().getDepartmentId() : null
        );
    }
}
