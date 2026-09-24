package com.jannetai.backend.entity.enums;

/**
 * Platform account roles (SRS Section 11; PROJECT_INTEGRATION.md Section 3).
 * Matches users.role's CHECK constraint in
 * database/migrations/V3__create_users.sql exactly - do not add/rename
 * values here without a corresponding migration.
 *
 * The SRS's eighth "role", AI Processing Engine, is a system actor with no
 * user row and is intentionally not a value here (see V3's header comment).
 */
public enum Role {
    CITIZEN,
    GOVERNMENT_OFFICER,
    DEPARTMENT_HEAD,
    ADMIN,
    SUPER_ADMIN,
    VERIFICATION_TEAM,
    MAINTENANCE_TEAM
}
