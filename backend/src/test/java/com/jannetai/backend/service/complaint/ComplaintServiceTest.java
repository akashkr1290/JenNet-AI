package com.jannetai.backend.service.complaint;

import com.jannetai.backend.dto.complaint.AssignmentRequest;
import com.jannetai.backend.entity.Budget;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.exception.GracePeriodExpiredException;
import com.jannetai.backend.exception.InvalidStateTransitionException;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.AuditLogRepository;
import com.jannetai.backend.repository.BudgetRepository;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.ImageRepository;
import com.jannetai.backend.repository.PredictionRepository;
import com.jannetai.backend.repository.StatusHistoryRepository;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.admin.PlatformSettingsService;
import com.jannetai.backend.service.notification.NotificationService;
import com.jannetai.backend.storage.ImageValidationService;
import com.jannetai.backend.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ComplaintService}, focused on the two areas this
 * project's own documentation flags as most safety-critical:
 *
 * <ol>
 *   <li>The Phase 13 department-scope security fix in {@link
 *       ComplaintService#reassign} and {@link ComplaintService#approveBudget}
 *       - PROJECT_INTEGRATION.md Section 6 records that these two actions
 *       had NO scope check at all from Phase 11 until Phase 13, letting a
 *       DEPARTMENT_HEAD act on any department's complaint by guessing an
 *       ID. These tests are the regression guard for that fix.</li>
 *   <li>The reopen grace-period gate (SRS-driven, {@code
 *       app.complaint.reopen-grace-period-days}).</li>
 * </ol>
 *
 * The constructor is called directly (not {@code @InjectMocks}) so the
 * 14-parameter order is explicit and unambiguous rather than relying on
 * Mockito's type-matching heuristic. {@code @Value}-injected fields
 * ({@code maxSubmissionsPer24h}, {@code reopenGracePeriodDays},
 * {@code budgetApprovalThresholdInr}) are set via {@code
 * ReflectionTestUtils} since they are not constructor parameters.
 *
 * NOT EXECUTED in this workspace (no Maven Central reach - see
 * PROJECT_PROGRESS.md Phase 20 TESTS section). Manually validated: every
 * method signature, field name, and repository method name referenced here
 * was cross-checked directly against ComplaintService.java and its entity/
 * repository dependencies.
 */
@ExtendWith(MockitoExtension.class)
class ComplaintServiceTest {

    @Mock private PriorityBudgetPredictionService priorityBudgetPredictionService;
    @Mock private DepartmentAssignmentService departmentAssignmentService;
    @Mock private ComplaintRepository complaintRepository;
    @Mock private ImageRepository imageRepository;
    @Mock private StatusHistoryRepository statusHistoryRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private BudgetRepository budgetRepository;
    @Mock private PredictionRepository predictionRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private LocationService locationService;
    @Mock private StorageService storageService;
    @Mock private ImageValidationService imageValidationService;
    @Mock private AuditService auditService;
    @Mock private PlatformSettingsService platformSettingsService;
    @Mock private NotificationService notificationService;

    private ComplaintService complaintService;

    @BeforeEach
    void setUp() {
        complaintService = new ComplaintService(
                priorityBudgetPredictionService,
                departmentAssignmentService,
                complaintRepository,
                imageRepository,
                statusHistoryRepository,
                auditLogRepository,
                budgetRepository,
                predictionRepository,
                departmentRepository,
                userRepository,
                locationService,
                storageService,
                imageValidationService,
                auditService,
                platformSettingsService,
                notificationService
        );
        ReflectionTestUtils.setField(complaintService, "maxSubmissionsPer24h", 5);
        ReflectionTestUtils.setField(complaintService, "reopenGracePeriodDays", 7);
        ReflectionTestUtils.setField(complaintService, "budgetApprovalThresholdInr", new BigDecimal("50000"));

        // save(...) round-trips its argument unchanged - the common case
        // every happy-path test below relies on.
        lenient().when(complaintRepository.save(any(Complaint.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private User citizen(long id) {
        return User.builder().userId(id).role(Role.CITIZEN).fullName("Citizen " + id).build();
    }

    private Department department(long id, String name) {
        return Department.builder().departmentId(id).name(name).isActive(true).build();
    }

    private User departmentHead(long id, Department dept) {
        return User.builder().userId(id).role(Role.DEPARTMENT_HEAD).department(dept)
                .fullName("Head " + id).build();
    }

    private Complaint complaintFor(User theCitizen, ComplaintStatus status, Department dept) {
        return Complaint.builder()
                .complaintId(100L)
                .citizen(theCitizen)
                .status(status)
                .department(dept)
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();
    }

    // ---- Phase 13 fix: reassign department-scope enforcement ----

    @Test
    void departmentHeadCannotReassignAComplaintOutsideTheirOwnDepartment() {
        Department ownDept = department(1L, "Roads");
        Department otherDept = department(2L, "Water");
        User head = departmentHead(10L, ownDept);
        Complaint complaint = complaintFor(citizen(5L), ComplaintStatus.VERIFIED, otherDept);
        when(complaintRepository.findById(100L)).thenReturn(Optional.of(complaint));

        AssignmentRequest request = new AssignmentRequest(otherDept.getDepartmentId(), null, null);

        assertThatThrownBy(() -> complaintService.reassign(head, 100L, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("outside your department");
    }

    @Test
    void departmentHeadCannotRetargetAComplaintToADifferentDepartmentThanTheirOwn() {
        // Even a complaint that IS in the head's own department cannot be
        // reassigned to name a *different* target department - this is the
        // second half of the Phase 13 fix (requireCanView alone isn't
        // enough; reassign has its own extra departmentId-match check).
        Department ownDept = department(1L, "Roads");
        Department otherDept = department(2L, "Water");
        User head = departmentHead(10L, ownDept);
        Complaint complaint = complaintFor(citizen(5L), ComplaintStatus.VERIFIED, ownDept);
        when(complaintRepository.findById(100L)).thenReturn(Optional.of(complaint));

        AssignmentRequest request = new AssignmentRequest(otherDept.getDepartmentId(), null, null);

        assertThatThrownBy(() -> complaintService.reassign(head, 100L, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("their own department");
    }

    @Test
    void departmentHeadCanReassignWithinTheirOwnDepartment() {
        Department ownDept = department(1L, "Roads");
        User head = departmentHead(10L, ownDept);
        Complaint complaint = complaintFor(citizen(5L), ComplaintStatus.VERIFIED, ownDept);
        when(complaintRepository.findById(100L)).thenReturn(Optional.of(complaint));
        when(departmentRepository.findById(ownDept.getDepartmentId())).thenReturn(Optional.of(ownDept));

        AssignmentRequest request = new AssignmentRequest(ownDept.getDepartmentId(), null, "Reassigning within team");

        var response = complaintService.reassign(head, 100L, request);

        assertThat(response).isNotNull();
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.ASSIGNED);
        verify(notificationService).notifyOfficerAssigned(complaint, null);
    }

    @Test
    void adminCanReassignAcrossAnyDepartment() {
        Department fromDept = department(1L, "Roads");
        Department toDept = department(2L, "Water");
        User admin = User.builder().userId(99L).role(Role.ADMIN).fullName("Admin").build();
        Complaint complaint = complaintFor(citizen(5L), ComplaintStatus.ASSIGNED, fromDept);
        when(complaintRepository.findById(100L)).thenReturn(Optional.of(complaint));
        when(departmentRepository.findById(toDept.getDepartmentId())).thenReturn(Optional.of(toDept));

        AssignmentRequest request = new AssignmentRequest(toDept.getDepartmentId(), null, null);

        var response = complaintService.reassign(admin, 100L, request);

        assertThat(response).isNotNull();
        assertThat(complaint.getDepartment()).isEqualTo(toDept);
        // Already ASSIGNED, not VERIFIED -> status is left unchanged, per
        // "next == previous" branch in reassign().
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.ASSIGNED);
    }

    @Test
    void reassignRejectsAComplaintThatIsAlreadyResolved() {
        Department dept = department(1L, "Roads");
        User admin = User.builder().userId(99L).role(Role.ADMIN).fullName("Admin").build();
        Complaint complaint = complaintFor(citizen(5L), ComplaintStatus.RESOLVED, dept);
        when(complaintRepository.findById(100L)).thenReturn(Optional.of(complaint));

        AssignmentRequest request = new AssignmentRequest(dept.getDepartmentId(), null, null);

        assertThatThrownBy(() -> complaintService.reassign(admin, 100L, request))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    // ---- Phase 13 fix: approveBudget department-scope enforcement ----

    @Test
    void departmentHeadCannotApproveBudgetForAComplaintOutsideTheirDepartment() {
        Department ownDept = department(1L, "Roads");
        Department otherDept = department(2L, "Water");
        User head = departmentHead(10L, ownDept);
        Complaint complaint = complaintFor(citizen(5L), ComplaintStatus.VERIFIED, otherDept);
        when(complaintRepository.findById(100L)).thenReturn(Optional.of(complaint));

        assertThatThrownBy(() -> complaintService.approveBudget(head, 100L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("outside your department");
    }

    @Test
    void departmentHeadCanApproveBudgetWithinTheirOwnDepartment() {
        Department ownDept = department(1L, "Roads");
        User head = departmentHead(10L, ownDept);
        Complaint complaint = complaintFor(citizen(5L), ComplaintStatus.VERIFIED, ownDept);
        Budget budget = Budget.builder().budgetId(1L).complaint(complaint)
                .estimatedCostMax(new BigDecimal("80000")).build();
        when(complaintRepository.findById(100L)).thenReturn(Optional.of(complaint));
        when(budgetRepository.findFirstByComplaint_ComplaintIdOrderByCreatedAtDescBudgetIdDesc(100L))
                .thenReturn(Optional.of(budget));
        lenient().when(budgetRepository.save(any(Budget.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = complaintService.approveBudget(head, 100L);

        assertThat(response).isNotNull();
        assertThat(budget.getApprovedBy()).isEqualTo(head);
    }

    @Test
    void approveBudgetThrowsWhenNoBudgetEstimateExistsYet() {
        Department dept = department(1L, "Roads");
        User head = departmentHead(10L, dept);
        Complaint complaint = complaintFor(citizen(5L), ComplaintStatus.VERIFIED, dept);
        when(complaintRepository.findById(100L)).thenReturn(Optional.of(complaint));
        when(budgetRepository.findFirstByComplaint_ComplaintIdOrderByCreatedAtDescBudgetIdDesc(100L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> complaintService.approveBudget(head, 100L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- Reopen grace period (SRS-driven business rule) ----

    @Test
    void citizenCanReopenWithinTheGracePeriod() {
        User theCitizen = citizen(5L);
        Complaint complaint = Complaint.builder()
                .complaintId(100L).citizen(theCitizen).status(ComplaintStatus.RESOLVED)
                .updatedAt(LocalDateTime.now().minusDays(2)) // within 7-day window
                .build();
        when(complaintRepository.findById(100L)).thenReturn(Optional.of(complaint));

        var response = complaintService.reopen(theCitizen, 100L);

        assertThat(response).isNotNull();
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.IN_PROGRESS);
        assertThat(complaint.getIsReopened()).isTrue();
    }

    @Test
    void reopenIsRejectedAfterTheGracePeriodExpires() {
        User theCitizen = citizen(5L);
        Complaint complaint = Complaint.builder()
                .complaintId(100L).citizen(theCitizen).status(ComplaintStatus.RESOLVED)
                .updatedAt(LocalDateTime.now().minusDays(10)) // past the 7-day window
                .build();
        when(complaintRepository.findById(100L)).thenReturn(Optional.of(complaint));

        assertThatThrownBy(() -> complaintService.reopen(theCitizen, 100L))
                .isInstanceOf(GracePeriodExpiredException.class);
    }

    @Test
    void citizenCannotReopenSomeoneElsesComplaint() {
        User owner = citizen(5L);
        User stranger = citizen(6L);
        Complaint complaint = Complaint.builder()
                .complaintId(100L).citizen(owner).status(ComplaintStatus.RESOLVED)
                .updatedAt(LocalDateTime.now())
                .build();
        when(complaintRepository.findById(100L)).thenReturn(Optional.of(complaint));

        assertThatThrownBy(() -> complaintService.reopen(stranger, 100L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("your own complaints");
    }

    @Test
    void reopenRejectsAComplaintThatIsNotResolvedOrClosed() {
        User theCitizen = citizen(5L);
        Complaint complaint = Complaint.builder()
                .complaintId(100L).citizen(theCitizen).status(ComplaintStatus.IN_PROGRESS)
                .updatedAt(LocalDateTime.now())
                .build();
        when(complaintRepository.findById(100L)).thenReturn(Optional.of(complaint));

        assertThatThrownBy(() -> complaintService.reopen(theCitizen, 100L))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void complaintNotFoundRaisesResourceNotFound() {
        when(complaintRepository.findById(999L)).thenReturn(Optional.empty());
        User theCitizen = citizen(5L);

        assertThatThrownBy(() -> complaintService.reopen(theCitizen, 999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
