package com.jannetai.backend.service.complaint;

import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.RoutingRule;
import com.jannetai.backend.entity.StatusHistory;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ActorType;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.RoutingRuleRepository;
import com.jannetai.backend.repository.StatusHistoryRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DepartmentAssignmentService} - SRS 15.7 routing (active rule vs. fallback
 * department) under the pilot workflow of 2026-09-30: a verified complaint is
 * routed to its department and WAITS for the Department Head, who assigns a
 * Government Officer. No officer is ever picked automatically.
 */
@ExtendWith(MockitoExtension.class)
class DepartmentAssignmentServiceTest {

    @Mock private RoutingRuleRepository routingRuleRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private ComplaintRepository complaintRepository;
    @Mock private StatusHistoryRepository statusHistoryRepository;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;

    private DepartmentAssignmentService service;

    @BeforeEach
    void setUp() {
        service = new DepartmentAssignmentService(routingRuleRepository, departmentRepository,
                complaintRepository, statusHistoryRepository, auditService, notificationService);
        ReflectionTestUtils.setField(service, "fallbackDepartmentName", "General Triage");
        lenient().when(complaintRepository.save(any(Complaint.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Complaint verifiedComplaint(ComplaintCategory category) {
        return Complaint.builder().complaintId(1L).referenceNumber("JN-2026-000001").category(category)
                .status(ComplaintStatus.VERIFIED).build();
    }

    @Test
    void routesToTheActiveRulesDepartmentAndWaitsForTheDepartmentHead() {
        Department publicWorks = Department.builder().departmentId(1L).name("Public Works").isActive(true).build();
        Complaint complaint = verifiedComplaint(ComplaintCategory.POTHOLE);
        when(routingRuleRepository.findCurrentActiveRule(ComplaintCategory.POTHOLE))
                .thenReturn(Optional.of(RoutingRule.builder().department(publicWorks).build()));

        service.routeToDepartment(complaint);

        assertThat(complaint.getDepartment()).isEqualTo(publicWorks);
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.VERIFIED); // ASSIGNED only once the Head picks an officer
        assertThat(complaint.getAssignedOfficer()).isNull();
        assertThat(complaint.getSlaDueAt()).isNull(); // no SLA clock before an officer is assigned
        verify(departmentRepository, never()).findByNameAndIsActiveTrue(any());
        verify(notificationService).notifyDepartmentAssignmentNeeded(complaint);
        verify(notificationService, never()).notifyOfficerAssigned(any(), any());
        verify(auditService).record(eq(null), eq("COMPLAINT_ROUTED_TO_DEPARTMENT"), eq("COMPLAINT"), eq(1L), any());
    }

    @Test
    void routingIsRecordedInTheComplaintHistory() {
        Department sanitation = Department.builder().departmentId(4L).name("Sanitation").isActive(true).build();
        Complaint complaint = verifiedComplaint(ComplaintCategory.GARBAGE_OVERFLOW);
        when(routingRuleRepository.findCurrentActiveRule(ComplaintCategory.GARBAGE_OVERFLOW))
                .thenReturn(Optional.of(RoutingRule.builder().department(sanitation).build()));

        service.routeToDepartment(complaint);

        ArgumentCaptor<StatusHistory> history = ArgumentCaptor.forClass(StatusHistory.class);
        verify(statusHistoryRepository).save(history.capture());
        assertThat(history.getValue().getPreviousStatus()).isEqualTo(ComplaintStatus.VERIFIED);
        assertThat(history.getValue().getNewStatus()).isEqualTo(ComplaintStatus.VERIFIED);
        assertThat(history.getValue().getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(history.getValue().getReason())
                .isEqualTo("Routed to Sanitation - awaiting Department Head assignment of a Government Officer");
    }

    @Test
    void aPreviouslySetOfficerIsNeverKeptByRouting() {
        Department publicWorks = Department.builder().departmentId(1L).name("Public Works").isActive(true).build();
        Complaint complaint = verifiedComplaint(ComplaintCategory.POTHOLE);
        complaint.setAssignedOfficer(User.builder().userId(9L).role(Role.GOVERNMENT_OFFICER)
                .status(UserStatus.ACTIVE).build());
        when(routingRuleRepository.findCurrentActiveRule(ComplaintCategory.POTHOLE))
                .thenReturn(Optional.of(RoutingRule.builder().department(publicWorks).build()));

        service.routeToDepartment(complaint);

        assertThat(complaint.getAssignedOfficer()).isNull();
    }

    @Test
    void fallsBackToTheConfiguredFallbackDepartmentWhenNoRuleMatches() {
        Department fallback = Department.builder().departmentId(9L).name("General Triage").isActive(true).build();
        Complaint complaint = verifiedComplaint(ComplaintCategory.GENERAL);
        when(routingRuleRepository.findCurrentActiveRule(ComplaintCategory.GENERAL)).thenReturn(Optional.empty());
        when(departmentRepository.findByNameAndIsActiveTrue("General Triage")).thenReturn(Optional.of(fallback));

        service.routeToDepartment(complaint);

        assertThat(complaint.getDepartment()).isEqualTo(fallback);
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.VERIFIED);
        verify(notificationService).notifyDepartmentAssignmentNeeded(complaint);
    }

    @Test
    void aDataAccessFailurePropagatesSoTheWholeAttemptRollsBackAndIsRetried() {
        // Audit GAP-059: a repository exception propagates (AiProcessingDispatcher
        // retries the attempt, audit GAP-010) and nothing is written first.
        Complaint complaint = verifiedComplaint(ComplaintCategory.POTHOLE);
        when(routingRuleRepository.findCurrentActiveRule(ComplaintCategory.POTHOLE))
                .thenThrow(new RuntimeException("routing table lookup exploded"));

        assertThatThrownBy(() -> service.routeToDepartment(complaint)).hasMessageContaining("exploded");

        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.VERIFIED);
        verify(complaintRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), anyLong(), any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void missingFallbackDepartmentIsAuditedAndLeavesTheComplaintUnrouted() {
        Complaint complaint = verifiedComplaint(ComplaintCategory.GENERAL);
        when(routingRuleRepository.findCurrentActiveRule(ComplaintCategory.GENERAL)).thenReturn(Optional.empty());
        when(departmentRepository.findByNameAndIsActiveTrue("General Triage")).thenReturn(Optional.empty());

        service.routeToDepartment(complaint);

        assertThat(complaint.getDepartment()).isNull();
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.VERIFIED);
        verify(auditService).record(eq(null), eq("DEPARTMENT_ASSIGNMENT_FAILED"), any(), anyLong(), any());
        verifyNoInteractions(notificationService);
    }
}
