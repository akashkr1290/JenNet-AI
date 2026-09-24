package com.jannetai.backend.entity.enums;

/**
 * Matches status_history.actor_type CHECK constraint
 * (V10__create_status_history.sql, widened by
 * V18__widen_status_history_actor_type.sql in Phase 6 to add
 * VERIFICATION_TEAM). NOTE: this is a distinct value set from {@link Role}
 * (e.g. OFFICER here vs. GOVERNMENT_OFFICER in Role; SYSTEM has no
 * equivalent Role at all; SUPER_ADMIN collapses to ADMIN here - see
 * ComplaintStateMachine's actor-type mapping) - do not conflate the two
 * enums.
 */
public enum ActorType {
    SYSTEM,
    CITIZEN,
    OFFICER,
    DEPARTMENT_HEAD,
    ADMIN,
    VERIFICATION_TEAM
}
