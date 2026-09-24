package com.jannetai.backend.service.complaint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.RoutingRule;
import com.jannetai.backend.entity.StatusHistory;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ActorType;
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
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 11 (Department Assignment Module, SRS 15.7).
 *
 * WIRING POINT - called immediately after {@link PriorityBudgetPredictionService#predictAndApply}
 * from both places a complaint reaches {@code VERIFIED}
 * ({@link AiClassificationService#applyAutoVerification} and
 * {@link ComplaintService#verify}'s {@code VERIFIED} branch), matching SRS
 * 14.2's own workflow diagram ordering: "... (Severity/Priority/Budget
 * Prediction) -&gt; Assigned -&gt; Officer Review ...". Unlike prediction,
 * this step has no external ai-service dependency - it is a pure internal
 * routing-table lookup plus an in-database load-balancing query - so it
 * does not need the same "never blocks the caller" tolerance built around
 * an unreliable external HTTP call; an unexpected failure here is still
 * caught and audited defensively (belt-and-suspenders, same posture as
 * every other step in this workflow) but is not expected in normal
 * operation.
 *
 * DEPENDENCY DIRECTION: this class deliberately does NOT depend on
 * {@link ComplaintService} (which depends on this class) - it writes its
 * own {@link StatusHistory} row directly via {@link StatusHistoryRepository}
 * rather than calling {@code ComplaintService.recordHistory}, to avoid a
 * circular Spring bean dependency. {@link PriorityBudgetPredictionService}
 * (Phase 10) never needed this because it never changes
 * {@code complaints.status} itself; this class does.
 *
 * ROUTING (SRS 15.7 Business Rules): resolves the category's current
 * active {@link RoutingRule} (per {@link RoutingRuleRepository}'s "latest
 * is_active row with effective_from &lt;= today" convention, V14's own
 * header comment) and assigns its department. If no active rule exists
 * for the category - true for every category today, since routing_rules
 * has zero seeded rows and rule creation is this same phase's new Admin
 * endpoint, not pre-populated data - the complaint falls back to the
 * configurable fallback department (SRS 15.7 Validation Rules:
 * "unmapped categories default to a configurable 'General Civic Issues'
 * department for manual triage"; V15 seeded this under the name
 * "General Triage" - see {@link DepartmentRepository#findByNameAndIsActiveTrue}'s
 * Javadoc).
 *
 * OFFICER LOAD-BALANCING (SRS 15.7 Business Rules: "officer assignment
 * considers current open-complaint load to balance workload"):
 * deliberately department-scoped only, not ward-scoped - see
 * PROJECT_INTEGRATION.md Section 6 for the full reasoning. "Open" means
 * ASSIGNED or IN_PROGRESS. Ties broken by lowest userId for determinism.
 *
 * NO-OFFICER-AVAILABLE (SRS 15.7 Exceptions): "if no officer in the
 * target department is available, the complaint remains 'Assigned' at
 * department level (unassigned to an individual officer) and is surfaced
 * on the Department Head's queue for manual officer assignment" - this
 * class assigns {@code department} unconditionally and leaves
 * {@code assignedOfficer} null when no eligible officer exists; it never
 * fails the transition for that reason alone.
 */
@Service
@RequiredArgsConstructor
public class DepartmentAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentAssignmentService.class);

    /** Statuses that count as "open" (still on an officer's plate) for load-balancing (SRS 15.7). */
    private static final Set<ComplaintStatus> OPEN_STATUSES = Set.of(
            ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS);

    private final RoutingRuleRepository routingRuleRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final ComplaintRepository complaintRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final AuditService auditService;
    private final NotificationService notificationService; // Phase 15: this class has its own recordHistory (see class Javadoc's DEPENDENCY DIRECTION note), so it calls NotificationService directly rather than via ComplaintService
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.department-assignment.fallback-department-name}")
    private String fallbackDepartmentName;

    /**
     * @param complaint must already be at {@code VERIFIED} with a real
     *                  category set - the caller (see class Javadoc)
     *                  guarantees this; called once, right after
     *                  {@link PriorityBudgetPredictionService#predictAndApply}
     *                  returns.
     */
    @Transactional
    public void assignAndApply(Complaint complaint) {
        try {
            Department department = resolveDepartment(complaint);
            User officer = selectOfficer(department);

            ComplaintStateMachine.assertSystemTransitionAllowed(ComplaintStatus.VERIFIED, ComplaintStatus.ASSIGNED);
            ComplaintStatus previous = complaint.getStatus();
            complaint.setDepartment(department);
            complaint.setAssignedOfficer(officer);
            complaint.setStatus(ComplaintStatus.ASSIGNED);
            complaint = complaintRepository.save(complaint);

            String reason = officer != null
                    ? "Auto-assigned to " + department.getName() + ", officer " + officer.getFullName()
                    : "Auto-assigned to " + department.getName() + " (no officer currently available - "
                            + "surfaced on Department Head's queue for manual officer assignment)";
            recordHistory(complaint, previous, ComplaintStatus.ASSIGNED, reason);

            auditService.record(null, "COMPLAINT_AUTO_ASSIGNED", "COMPLAINT", complaint.getComplaintId(),
                    toJson(Map.of(
                            "department_id", department.getDepartmentId(),
                            "department_name", department.getName(),
                            "officer_id", officer != null ? officer.getUserId() : 0L,
                            "officer_assigned", officer != null)));

            log.info("Complaint {} assigned to department {} (officer={})",
                    complaint.getComplaintId(), department.getName(),
                    officer != null ? officer.getUserId() : "none available");

            // Phase 15: this path (auto-assign) has its own recordHistory above,
            // separate from ComplaintService's - so unlike the manual reassign/
            // verify paths, nothing else notifies the citizen of this ASSIGNED
            // transition unless this class calls NotificationService itself.
            notificationService.notifyComplaintStatusChanged(complaint, previous, ComplaintStatus.ASSIGNED);
            notificationService.notifyOfficerAssigned(complaint, officer);
        } catch (RuntimeException e) {
            // Defensive only (see class Javadoc) - a routing-table lookup
            // has no unreliable external dependency, so this is not
            // expected in normal operation, but must never leave a
            // VERIFIED complaint's transaction in an inconsistent state.
            log.error("Department assignment failed for complaint {}: {}", complaint.getComplaintId(), e.getMessage(), e);
            auditService.record(null, "DEPARTMENT_ASSIGNMENT_FAILED", "COMPLAINT", complaint.getComplaintId(),
                    toJson(Map.of("error", nullToEmpty(e.getMessage()))));
        }
    }

    private Department resolveDepartment(Complaint complaint) {
        return routingRuleRepository.findCurrentActiveRule(complaint.getCategory())
                .map(RoutingRule::getDepartment)
                .orElseGet(this::requireFallbackDepartment);
    }

    private Department requireFallbackDepartment() {
        return departmentRepository.findByNameAndIsActiveTrue(fallbackDepartmentName)
                .orElseThrow(() -> new IllegalStateException(
                        "No active routing rule matched and the configured fallback department ('"
                                + fallbackDepartmentName + "') does not exist - check "
                                + "app.department-assignment.fallback-department-name and the departments table"));
    }

    /** SRS 15.7: picks the GOVERNMENT_OFFICER in {@code department} with the fewest open complaints; null if none. */
    private User selectOfficer(Department department) {
        List<User> eligible = userRepository.findByRoleAndDepartment_DepartmentIdAndStatus(
                Role.GOVERNMENT_OFFICER, department.getDepartmentId(), UserStatus.ACTIVE);
        if (eligible.isEmpty()) {
            return null;
        }
        return eligible.stream()
                .min(Comparator
                        .comparingLong((User u) -> complaintRepository.countByAssignedOfficer_UserIdAndStatusIn(
                                u.getUserId(), OPEN_STATUSES))
                        .thenComparing(User::getUserId))
                .orElse(null);
    }

    private void recordHistory(Complaint complaint, ComplaintStatus previous, ComplaintStatus next, String reason) {
        StatusHistory history = StatusHistory.builder()
                .complaint(complaint)
                .previousStatus(previous)
                .newStatus(next)
                .actor(null)
                .actorType(ActorType.SYSTEM)
                .reason(reason)
                .build();
        statusHistoryRepository.save(history);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize department-assignment audit payload: {}", e.getMessage());
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
