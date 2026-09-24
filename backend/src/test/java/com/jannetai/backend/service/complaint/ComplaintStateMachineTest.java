package com.jannetai.backend.service.complaint;

import com.jannetai.backend.entity.enums.ActorType;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ComplaintStateMachine} - the single source of truth
 * for the complaint lifecycle (ARCHITECTURE.md Section 4). Pure static
 * logic, no Spring context or mocking needed.
 *
 * NOT EXECUTED in this workspace (no Maven Central network reach to
 * resolve spring-boot-starter-test/JUnit5/AssertJ - see PROJECT_PROGRESS.md
 * Phase 20 TESTS section). Manually validated: every enum constant, method
 * signature, and exception type referenced here was cross-checked against
 * ComplaintStateMachine.java, ComplaintStatus.java, Role.java, and
 * ActorType.java source directly.
 */
class ComplaintStateMachineTest {

    // --- Happy-path transitions, one per documented edge ---

    @Test
    void systemTransitionSubmittedToAiProcessingIsAllowed() {
        ComplaintStateMachine.assertSystemTransitionAllowed(
                ComplaintStatus.SUBMITTED, ComplaintStatus.AI_PROCESSING);
        // no exception = pass
    }

    @Test
    void verificationTeamCanVerifyFromAiProcessing() {
        ComplaintStateMachine.assertTransitionAllowed(
                ComplaintStatus.AI_PROCESSING, ComplaintStatus.VERIFIED, Role.VERIFICATION_TEAM);
    }

    @Test
    void adminCanRejectFromAiProcessing() {
        ComplaintStateMachine.assertTransitionAllowed(
                ComplaintStatus.AI_PROCESSING, ComplaintStatus.REJECTED, Role.ADMIN);
    }

    @Test
    void superAdminCanMarkDuplicateFromAiProcessing() {
        ComplaintStateMachine.assertTransitionAllowed(
                ComplaintStatus.AI_PROCESSING, ComplaintStatus.DUPLICATE, Role.SUPER_ADMIN);
    }

    @Test
    void departmentHeadCanMoveVerifiedToAssigned() {
        ComplaintStateMachine.assertTransitionAllowed(
                ComplaintStatus.VERIFIED, ComplaintStatus.ASSIGNED, Role.DEPARTMENT_HEAD);
    }

    @Test
    void officerCanMoveAssignedToInProgress() {
        ComplaintStateMachine.assertTransitionAllowed(
                ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS, Role.GOVERNMENT_OFFICER);
    }

    @Test
    void maintenanceTeamCanResolveFromInProgress() {
        ComplaintStateMachine.assertTransitionAllowed(
                ComplaintStatus.IN_PROGRESS, ComplaintStatus.RESOLVED, Role.MAINTENANCE_TEAM);
    }

    @Test
    void citizenCanCloseFromResolved() {
        ComplaintStateMachine.assertTransitionAllowed(
                ComplaintStatus.RESOLVED, ComplaintStatus.CLOSED, Role.CITIZEN);
    }

    @Test
    void citizenCanReopenFromResolved() {
        ComplaintStateMachine.assertTransitionAllowed(
                ComplaintStatus.RESOLVED, ComplaintStatus.IN_PROGRESS, Role.CITIZEN);
    }

    @Test
    void citizenCanReopenFromClosed() {
        ComplaintStateMachine.assertTransitionAllowed(
                ComplaintStatus.CLOSED, ComplaintStatus.IN_PROGRESS, Role.CITIZEN);
    }

    // --- SRS 15.3 rule: Rejected is only reachable prior to In Progress ---

    @Test
    void rejectedIsNotReachableFromInProgress() {
        assertThatThrownBy(() -> ComplaintStateMachine.assertTransitionAllowed(
                ComplaintStatus.IN_PROGRESS, ComplaintStatus.REJECTED, Role.ADMIN))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void assignedCanStillReject_confirmsTheCutoffIsExactlyAtInProgress() {
        // ASSIGNED -> REJECTED IS a documented edge (department discovers an
        // invalid assignment before work starts) - opposite-direction sanity
        // check from the IN_PROGRESS test above, confirming the "no reject
        // once In Progress" boundary sits exactly at IN_PROGRESS, not earlier.
        ComplaintStateMachine.assertTransitionAllowed(
                ComplaintStatus.ASSIGNED, ComplaintStatus.REJECTED, Role.DEPARTMENT_HEAD);
    }

    // --- Terminal states: no outgoing transitions ---

    @Test
    void duplicateIsTerminal() {
        assertThatThrownBy(() -> ComplaintStateMachine.assertSystemTransitionAllowed(
                ComplaintStatus.DUPLICATE, ComplaintStatus.IN_PROGRESS))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void rejectedIsTerminal() {
        assertThatThrownBy(() -> ComplaintStateMachine.assertSystemTransitionAllowed(
                ComplaintStatus.REJECTED, ComplaintStatus.VERIFIED))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    // --- Structurally-invalid transitions (skips entirely) ---

    @Test
    void cannotSkipFromSubmittedDirectlyToVerified() {
        assertThatThrownBy(() -> ComplaintStateMachine.assertSystemTransitionAllowed(
                ComplaintStatus.SUBMITTED, ComplaintStatus.VERIFIED))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void cannotMoveBackwardsFromResolvedToAssigned() {
        assertThatThrownBy(() -> ComplaintStateMachine.assertTransitionAllowed(
                ComplaintStatus.RESOLVED, ComplaintStatus.ASSIGNED, Role.ADMIN))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    // --- Role-gating: structurally-valid transition, wrong actor ---

    @Test
    void citizenCannotVerifyAComplaint() {
        assertThatThrownBy(() -> ComplaintStateMachine.assertTransitionAllowed(
                ComplaintStatus.AI_PROCESSING, ComplaintStatus.VERIFIED, Role.CITIZEN))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void officerCannotCloseAResolvedComplaint() {
        // Only CITIZEN/ADMIN/SUPER_ADMIN may close per ACTOR_ROLES.
        assertThatThrownBy(() -> ComplaintStateMachine.assertTransitionAllowed(
                ComplaintStatus.RESOLVED, ComplaintStatus.CLOSED, Role.GOVERNMENT_OFFICER))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void citizenCannotAssignADepartment() {
        // VERIFIED -> ASSIGNED's ACTOR_ROLES is
        // {ADMIN, SUPER_ADMIN, DEPARTMENT_HEAD, VERIFICATION_TEAM} - CITIZEN
        // is genuinely excluded, unlike VERIFICATION_TEAM which IS allowed.
        assertThatThrownBy(() -> ComplaintStateMachine.assertTransitionAllowed(
                ComplaintStatus.VERIFIED, ComplaintStatus.ASSIGNED, Role.CITIZEN))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    // --- actorTypeFor mapping (ActorType is a distinct enum from Role) ---

    @Test
    void actorTypeForCitizenIsCitizen() {
        assertThat(ComplaintStateMachine.actorTypeFor(Role.CITIZEN)).isEqualTo(ActorType.CITIZEN);
    }

    @Test
    void actorTypeForGovernmentOfficerIsOfficer() {
        assertThat(ComplaintStateMachine.actorTypeFor(Role.GOVERNMENT_OFFICER)).isEqualTo(ActorType.OFFICER);
    }

    @Test
    void actorTypeForMaintenanceTeamCollapsesToOfficer() {
        assertThat(ComplaintStateMachine.actorTypeFor(Role.MAINTENANCE_TEAM)).isEqualTo(ActorType.OFFICER);
    }

    @Test
    void actorTypeForSuperAdminCollapsesToAdmin() {
        assertThat(ComplaintStateMachine.actorTypeFor(Role.SUPER_ADMIN)).isEqualTo(ActorType.ADMIN);
    }

    @Test
    void actorTypeForAdminIsAdmin() {
        assertThat(ComplaintStateMachine.actorTypeFor(Role.ADMIN)).isEqualTo(ActorType.ADMIN);
    }

    @Test
    void actorTypeForDepartmentHeadIsDepartmentHead() {
        assertThat(ComplaintStateMachine.actorTypeFor(Role.DEPARTMENT_HEAD)).isEqualTo(ActorType.DEPARTMENT_HEAD);
    }

    @Test
    void actorTypeForVerificationTeamIsVerificationTeam() {
        assertThat(ComplaintStateMachine.actorTypeFor(Role.VERIFICATION_TEAM)).isEqualTo(ActorType.VERIFICATION_TEAM);
    }

    /**
     * Every {@link Role} must map to some {@link ActorType} without
     * throwing - actorTypeFor's switch has no default branch, so an
     * un-mapped future Role addition would be a compile error, not a
     * runtime one; this test exists as a runtime backstop documenting the
     * expectation for whoever eventually reads it.
     */
    @ParameterizedTest
    @EnumSource(Role.class)
    void everyRoleMapsToAnActorTypeWithoutThrowing(Role role) {
        assertThat(ComplaintStateMachine.actorTypeFor(role)).isNotNull();
    }
}
