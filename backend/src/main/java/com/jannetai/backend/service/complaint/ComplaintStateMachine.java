package com.jannetai.backend.service.complaint;

import com.jannetai.backend.entity.enums.ActorType;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.exception.InvalidStateTransitionException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The complaint lifecycle transition table (ARCHITECTURE.md Section 4:
 * "This is a strict state machine - arbitrary transitions are not
 * permitted... The exact transition table... is finalized and implemented
 * in Phase 6"). This class is the single source of truth for which
 * status -> status moves are legal and which {@link Role}s may perform
 * them via an API action; ComplaintService must not persist a status
 * change without going through this class.
 *
 * DECISION - ESCALATED/REOPENED are annotations, not statuses: V6's
 * header comment explicitly left this choice open for Phase 6 ("Phase 6
 * ... should pick one approach"). The SRS's own behavioral description
 * ("Escalated: an annotation (not a terminal state) ... the underlying
 * status continues to progress normally once escalated"; reopen "resets
 * status to In Progress with a reopen flag") only makes sense if
 * ESCALATED/REOPENED are never themselves persisted into
 * {@code complaints.status} - they're what {@code is_escalated}/
 * {@code is_reopened} + their timestamp columns are for. This state
 * machine therefore never transitions a complaint INTO
 * ComplaintStatus.ESCALATED or ComplaintStatus.REOPENED; those two enum
 * values remain valid per the locked 12-value list (and the DB CHECK
 * constraint isn't narrowed - a harmless unused allowance, safer than a
 * migration that removes something later code might still want) but are
 * simply never chosen as a transition target by this class. See
 * ComplaintService.reopen for where is_reopened/reopened_at are actually
 * set.
 *
 * SCOPE NOTE: states beyond what Phase 6's own endpoints can reach (e.g.
 * ASSIGNED, which needs a non-null department_id from the Department
 * Assignment Module, Phase 11) are still encoded structurally so later
 * phases build against an already-agreed table instead of redesigning
 * one, per ARCHITECTURE.md's explicit instruction - Phase 6's own code
 * only exercises SUBMITTED -> AI_PROCESSING -> {VERIFIED, REJECTED,
 * DUPLICATE} and the RESOLVED/CLOSED -> IN_PROGRESS reopen edge.
 */
public final class ComplaintStateMachine {

    private static final Map<ComplaintStatus, Set<ComplaintStatus>> TRANSITIONS = new EnumMap<>(ComplaintStatus.class);
    private static final Map<ComplaintStatus, Set<Role>> ACTOR_ROLES = new EnumMap<>(ComplaintStatus.class);

    static {
        // SUBMITTED -> AI_PROCESSING is system-initiated (immediately,
        // synchronously, right after creation - see ComplaintService.create)
        // and is not reachable through any role-gated API action, so it has
        // no ACTOR_ROLES entry; use assertSystemTransitionAllowed for it.
        TRANSITIONS.put(ComplaintStatus.SUBMITTED, EnumSet.of(ComplaintStatus.AI_PROCESSING));

        // AI_PROCESSING is where the (not-yet-built) AI Analysis Module
        // would act automatically. Phase 6's approved manual override lets
        // the Verification Team (or Admin/Super Admin) make the same call
        // by hand in the meantime (PATCH /api/v1/complaints/{id}/verify).
        TRANSITIONS.put(ComplaintStatus.AI_PROCESSING, EnumSet.of(
                ComplaintStatus.VERIFIED, ComplaintStatus.REJECTED, ComplaintStatus.DUPLICATE));
        ACTOR_ROLES.put(ComplaintStatus.AI_PROCESSING, EnumSet.of(
                Role.VERIFICATION_TEAM, Role.ADMIN, Role.SUPER_ADMIN));

        // VERIFIED -> ASSIGNED needs a non-null department_id (Department
        // Assignment Module, Phase 11) - ComplaintService enforces that
        // precondition before calling this class; not reachable via any
        // Phase 6 endpoint.
        TRANSITIONS.put(ComplaintStatus.VERIFIED, EnumSet.of(
                ComplaintStatus.ASSIGNED, ComplaintStatus.REJECTED));
        ACTOR_ROLES.put(ComplaintStatus.VERIFIED, EnumSet.of(
                Role.ADMIN, Role.SUPER_ADMIN, Role.DEPARTMENT_HEAD, Role.VERIFICATION_TEAM));

        TRANSITIONS.put(ComplaintStatus.ASSIGNED, EnumSet.of(
                ComplaintStatus.IN_PROGRESS, ComplaintStatus.REJECTED));
        ACTOR_ROLES.put(ComplaintStatus.ASSIGNED, EnumSet.of(
                Role.GOVERNMENT_OFFICER, Role.DEPARTMENT_HEAD, Role.ADMIN, Role.SUPER_ADMIN));

        // SRS 15.3: Rejected is only reachable "prior to In Progress" - no
        // REJECTED target from IN_PROGRESS onward.
        TRANSITIONS.put(ComplaintStatus.IN_PROGRESS, EnumSet.of(ComplaintStatus.RESOLVED));
        ACTOR_ROLES.put(ComplaintStatus.IN_PROGRESS, EnumSet.of(
                Role.GOVERNMENT_OFFICER, Role.MAINTENANCE_TEAM, Role.DEPARTMENT_HEAD,
                Role.ADMIN, Role.SUPER_ADMIN));

        // RESOLVED -> CLOSED (citizen confirms, or system auto-closes after
        // the grace period - system path uses assertSystemTransitionAllowed,
        // not built out this phase, see KNOWN LIMITATIONS) and
        // RESOLVED -> IN_PROGRESS (citizen reopen; is_reopened/reopened_at
        // set by ComplaintService.reopen, status target is IN_PROGRESS
        // itself per the ESCALATED/REOPENED-as-annotation decision above).
        TRANSITIONS.put(ComplaintStatus.RESOLVED, EnumSet.of(
                ComplaintStatus.CLOSED, ComplaintStatus.IN_PROGRESS));
        ACTOR_ROLES.put(ComplaintStatus.RESOLVED, EnumSet.of(
                Role.CITIZEN, Role.ADMIN, Role.SUPER_ADMIN));

        // Audit GAP-052: CLOSED is terminal (SRS 15.3 "Closed/Rejected are
        // terminal, retained states"; workflow table "Closed - Terminal state").
        // A citizen reopens from RESOLVED within the grace period; once closed
        // (by confirmation or automatic closure after the grace period) it
        // cannot be reopened. Previously CLOSED -> IN_PROGRESS was allowed.
        TRANSITIONS.put(ComplaintStatus.CLOSED, EnumSet.noneOf(ComplaintStatus.class));

        // Terminal, no outgoing transitions.
        TRANSITIONS.put(ComplaintStatus.DUPLICATE, EnumSet.noneOf(ComplaintStatus.class));
        TRANSITIONS.put(ComplaintStatus.REJECTED, EnumSet.noneOf(ComplaintStatus.class));
    }

    private ComplaintStateMachine() {
    }

    /**
     * Validates a role-initiated transition (an authenticated user calling
     * a Complaint Module API action).
     *
     * @throws InvalidStateTransitionException if {@code from -> to} isn't a
     *                                          legal transition, or
     *                                          {@code actingRole} isn't
     *                                          permitted to perform it
     */
    public static void assertTransitionAllowed(ComplaintStatus from, ComplaintStatus to, Role actingRole) {
        assertStructurallyValid(from, to);
        Set<Role> allowedRoles = ACTOR_ROLES.get(from);
        if (allowedRoles == null || !allowedRoles.contains(actingRole)) {
            throw new InvalidStateTransitionException(
                    "Role " + actingRole + " is not permitted to transition a complaint out of " + from);
        }
    }

    /**
     * Validates a system-initiated transition (no human actor, e.g.
     * SUBMITTED -> AI_PROCESSING immediately after creation). Skips the
     * role check since no {@link Role} is involved.
     */
    /** Roles that may decide an appeal (ComplaintController's appeal review endpoint). */
    private static final Set<Role> APPEAL_REVIEWER_ROLES = EnumSet.of(
            Role.VERIFICATION_TEAM, Role.DEPARTMENT_HEAD, Role.ADMIN, Role.SUPER_ADMIN);

    /**
     * Audit GAP-030 (SRS 14.1 step 28, 14.3 "appealed exactly once"): the ONLY
     * way out of REJECTED - an APPROVED appeal sends the complaint back to
     * AI_PROCESSING, the Verification Team's queue, for re-verification.
     * Deliberately not part of the general transition table, so no status
     * update or verify call can ever leave REJECTED.
     */
    public static void assertAppealReverificationAllowed(ComplaintStatus from, Role actingRole) {
        if (from != ComplaintStatus.REJECTED) {
            throw new InvalidStateTransitionException(
                    "Only a REJECTED complaint can be sent back for re-verification (current status: " + from + ")");
        }
        if (actingRole == null || !APPEAL_REVIEWER_ROLES.contains(actingRole)) {
            throw new InvalidStateTransitionException("Role " + actingRole + " may not decide appeals");
        }
    }

    public static void assertSystemTransitionAllowed(ComplaintStatus from, ComplaintStatus to) {
        assertStructurallyValid(from, to);
    }

    private static void assertStructurallyValid(ComplaintStatus from, ComplaintStatus to) {
        Set<ComplaintStatus> allowedTargets = TRANSITIONS.get(from);
        if (allowedTargets == null || !allowedTargets.contains(to)) {
            throw new InvalidStateTransitionException(
                    "Cannot transition complaint from " + from + " to " + to);
        }
    }

    /**
     * Maps a platform {@link Role} onto the distinct {@link ActorType}
     * value set used by status_history (see ActorType's own Javadoc for
     * why these aren't the same enum - SUPER_ADMIN collapses to ADMIN,
     * MAINTENANCE_TEAM collapses to OFFICER, neither has its own
     * ActorType value).
     */
    public static ActorType actorTypeFor(Role role) {
        return switch (role) {
            case CITIZEN -> ActorType.CITIZEN;
            case GOVERNMENT_OFFICER -> ActorType.OFFICER;
            case DEPARTMENT_HEAD -> ActorType.DEPARTMENT_HEAD;
            case ADMIN, SUPER_ADMIN -> ActorType.ADMIN;
            case VERIFICATION_TEAM -> ActorType.VERIFICATION_TEAM;
            case MAINTENANCE_TEAM -> ActorType.OFFICER;
        };
    }
}
