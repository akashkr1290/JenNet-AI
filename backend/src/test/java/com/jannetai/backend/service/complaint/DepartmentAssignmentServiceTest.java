package com.jannetai.backend.service.complaint;

import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.RoutingRule;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.RoutingRuleRepository;
import com.jannetai.backend.repository.StatusHistoryRepository;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DepartmentAssignmentService} - SRS 15.7 routing
 * (active rule vs. fallback department) and officer load-balancing
 * (fewest open complaints, ties broken by lowest userId).
 *
 * NOT EXECUTED in this workspace (no Maven Central reach - see
 * PROJECT_PROGRESS.md's Phase 20 TESTS section). Manually validated
 * against DepartmentAssignmentService.java's actual method/repository
 * signatures.
 */
@ExtendWith(MockitoExtension.class)
class DepartmentAssignmentServiceTest {

    @Mock private RoutingRuleRepository routingRuleRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private ComplaintRepository complaintRepository;
    @Mock private StatusHistoryRepository statusHistoryRepository;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;

    private DepartmentAssignmentService service;

    @BeforeEach
    void setUp() {
        service = new DepartmentAssignmentService(
                routingRuleRepository, departmentRepository, userRepository,
                complaintRepository, statusHistoryRepository, auditService, notificationService);
        ReflectionTestUtils.setField(service, "fallbackDepartmentName", "General Triage");
        lenient().when(complaintRepository.save(any(Complaint.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Complaint verifiedComplaint(ComplaintCategory category) {
        return Complaint.builder().complaintId(1L).category(category)
                .status(ComplaintStatus.VERIFIED).build();
    }

    private User officer(long id, String name) {
        return User.builder().userId(id).role(Role.GOVERNMENT_OFFICER).status(UserStatus.ACTIVE)
                .fullName(name).build();
    }

    @Test
    void routesToTheActiveRulesDepartmentWhenOneExists() {
        Department roads = Department.builder().departmentId(1L).name("Roads").isActive(true).build();
        RoutingRule rule = RoutingRule.builder().department(roads).build();
        Complaint complaint = verifiedComplaint(ComplaintCategory.POTHOLE);
        when(routingRuleRepository.findCurrentActiveRule(ComplaintCategory.POTHOLE))
                .thenReturn(Optional.of(rule));
        when(userRepository.findByRoleAndDepartment_DepartmentIdAndStatus(
                Role.GOVERNMENT_OFFICER, 1L, UserStatus.ACTIVE)).thenReturn(List.of());

        service.assignAndApply(complaint);

        assertThat(complaint.getDepartment()).isEqualTo(roads);
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.ASSIGNED);
        verify(departmentRepository, never()).findByNameAndIsActiveTrue(any());
    }

    @Test
    void fallsBackToTheConfiguredFallbackDepartmentWhenNoRuleMatches() {
        Department fallback = Department.builder().departmentId(9L).name("General Triage").isActive(true).build();
        Complaint complaint = verifiedComplaint(ComplaintCategory.GENERAL);
        when(routingRuleRepository.findCurrentActiveRule(ComplaintCategory.GENERAL)).thenReturn(Optional.empty());
        when(departmentRepository.findByNameAndIsActiveTrue("General Triage")).thenReturn(Optional.of(fallback));
        when(userRepository.findByRoleAndDepartment_DepartmentIdAndStatus(
                Role.GOVERNMENT_OFFICER, 9L, UserStatus.ACTIVE)).thenReturn(List.of());

        service.assignAndApply(complaint);

        assertThat(complaint.getDepartment()).isEqualTo(fallback);
    }

    @Test
    void assignsToTheOfficerWithTheFewestOpenComplaints() {
        Department roads = Department.builder().departmentId(1L).name("Roads").isActive(true).build();
        RoutingRule rule = RoutingRule.builder().department(roads).build();
        Complaint complaint = verifiedComplaint(ComplaintCategory.POTHOLE);
        User busyOfficer = officer(1L, "Busy");
        User freeOfficer = officer(2L, "Free");
        when(routingRuleRepository.findCurrentActiveRule(ComplaintCategory.POTHOLE))
                .thenReturn(Optional.of(rule));
        when(userRepository.findByRoleAndDepartment_DepartmentIdAndStatus(
                Role.GOVERNMENT_OFFICER, 1L, UserStatus.ACTIVE)).thenReturn(List.of(busyOfficer, freeOfficer));
        when(complaintRepository.countByAssignedOfficer_UserIdAndStatusIn(eq(1L), any())).thenReturn(5L);
        when(complaintRepository.countByAssignedOfficer_UserIdAndStatusIn(eq(2L), any())).thenReturn(1L);

        service.assignAndApply(complaint);

        assertThat(complaint.getAssignedOfficer()).isEqualTo(freeOfficer);
        verify(notificationService).notifyOfficerAssigned(complaint, freeOfficer);
        verify(notificationService, never()).notifyNoOfficerAvailable(any());
    }

    @Test
    void tiesInOpenComplaintCountAreBrokenByLowestUserId() {
        Department roads = Department.builder().departmentId(1L).name("Roads").isActive(true).build();
        RoutingRule rule = RoutingRule.builder().department(roads).build();
        Complaint complaint = verifiedComplaint(ComplaintCategory.POTHOLE);
        User higherId = officer(5L, "Higher");
        User lowerId = officer(2L, "Lower");
        when(routingRuleRepository.findCurrentActiveRule(ComplaintCategory.POTHOLE))
                .thenReturn(Optional.of(rule));
        when(userRepository.findByRoleAndDepartment_DepartmentIdAndStatus(
                Role.GOVERNMENT_OFFICER, 1L, UserStatus.ACTIVE)).thenReturn(List.of(higherId, lowerId));
        when(complaintRepository.countByAssignedOfficer_UserIdAndStatusIn(any(), any())).thenReturn(0L);

        service.assignAndApply(complaint);

        assertThat(complaint.getAssignedOfficer()).isEqualTo(lowerId);
    }

    @Test
    void leavesComplaintUnassignedToAnIndividualOfficerWhenNoneAreEligible_butStillAssignsDepartment() {
        Department roads = Department.builder().departmentId(1L).name("Roads").isActive(true).build();
        RoutingRule rule = RoutingRule.builder().department(roads).build();
        Complaint complaint = verifiedComplaint(ComplaintCategory.POTHOLE);
        when(routingRuleRepository.findCurrentActiveRule(ComplaintCategory.POTHOLE))
                .thenReturn(Optional.of(rule));
        when(userRepository.findByRoleAndDepartment_DepartmentIdAndStatus(
                Role.GOVERNMENT_OFFICER, 1L, UserStatus.ACTIVE)).thenReturn(List.of());

        service.assignAndApply(complaint);

        assertThat(complaint.getDepartment()).isEqualTo(roads);
        assertThat(complaint.getAssignedOfficer()).isNull();
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.ASSIGNED);
        verify(notificationService).notifyOfficerAssigned(complaint, null);
        verify(notificationService).notifyNoOfficerAvailable(complaint); // audit GAP-023
    }

    @Test
    void aDataAccessFailurePropagatesSoTheWholeAttemptRollsBackAndIsRetried() {
        // Audit GAP-059: previously caught here - but a repository exception has
        // already marked the joined transaction rollback-only, so the caller's
        // commit then failed with UnexpectedRollbackException. It now propagates
        // (AiProcessingDispatcher retries the attempt, audit GAP-010) and nothing
        // is written first.
        Complaint complaint = verifiedComplaint(ComplaintCategory.POTHOLE);
        when(routingRuleRepository.findCurrentActiveRule(ComplaintCategory.POTHOLE))
                .thenThrow(new RuntimeException("routing table lookup exploded"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.assignAndApply(complaint))
                .hasMessageContaining("exploded");

        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.VERIFIED); // unchanged
        verify(complaintRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), anyLong(), any());
    }

    @Test
    void missingFallbackDepartmentIsAlsoCaughtDefensively() {
        Complaint complaint = verifiedComplaint(ComplaintCategory.GENERAL);
        when(routingRuleRepository.findCurrentActiveRule(ComplaintCategory.GENERAL)).thenReturn(Optional.empty());
        when(departmentRepository.findByNameAndIsActiveTrue("General Triage")).thenReturn(Optional.empty());

        service.assignAndApply(complaint);

        assertThat(complaint.getDepartment()).isNull();
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.VERIFIED);
        verify(auditService).record(eq(null), eq("DEPARTMENT_ASSIGNMENT_FAILED"), any(), anyLong(), any());
    }
}
