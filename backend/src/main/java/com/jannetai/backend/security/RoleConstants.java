package com.jannetai.backend.security;

import com.jannetai.backend.entity.enums.Role;

/**
 * Maps {@link Role} values to Spring Security authority strings
 * ("ROLE_" + name), and defines the role groups used by
 * SecurityConfig/@PreAuthorize across this and later phases so a single
 * place governs which roles a given admin-tier action requires.
 */
public final class RoleConstants {

    private RoleConstants() {
    }

    public static String authority(Role role) {
        return "ROLE_" + role.name();
    }

    /** SRS 27.1: MFA (OTP + password) required for these two roles at login. */
    public static boolean requiresMfa(Role role) {
        return role == Role.ADMIN || role == Role.SUPER_ADMIN;
    }
}
