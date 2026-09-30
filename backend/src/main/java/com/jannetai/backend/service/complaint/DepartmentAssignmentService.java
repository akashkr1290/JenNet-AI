package com.jannetai.backend.service.complaint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.RoutingRule;
import com.jannetai.backend.entity.StatusHistory;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ActorType;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.RoutingRuleRepository;
import com.jannetai.backend.repository.StatusHistoryRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Phase 11 (Department Assignment Module, SRS 15.7) - DEPARTMENT ROUTING only.
 *
 * <p>Pilot workflow decision (2026-09-30, product owner): a verified complaint
 * goes to its <b>department</b>, and the <b>Department Head assigns a Government
 * Officer</b> (ComplaintService#reassign, PATCH /complaints/{id}/assign). The
 * previous automatic least-loaded officer selection (SRS 15.7 "officer
 * load balancing") bypassed the Department Head and is no longer done - a
 * documented deviation from SRS 15.7.
 *
 * <p>STATUS: the complaint stays {@code VERIFIED} with {@code department} set
 * and no officer ("awaiting Department Head assignment"); it becomes
 * {@code ASSIGNED} only when the Department Head names an active officer of the
 * same department, which is also when the SLA clock starts (V27 semantics:
 * the clock starts on entering ASSIGNED).
 *
 * <p>WIRING POINT - called immediately after
 * {@link PriorityBudgetPredictionService#predictAndApply} from both places a
 * complaint reaches {@code VERIFIED} ({@link AiClassificationService}'s
 * auto-verification at or above the AI confidence threshold, and
 * {@link ComplaintService#verify}'s manual Verification Team decision).
 *
 * <p>DEPENDENCY DIRECTION: this class deliberately does NOT depend on
 * {@link ComplaintService} (which depends on this class) - it writes its own
 * {@link StatusHistory} row directly to avoid a circular bean dependency.
 *
 * <p>ROUTING (SRS 15.7 Business Rules): the category's current active
 * {@link RoutingRule}; if none exists the configurable fallback department
 * ('General Triage', SRS 15.7 Exceptions) for manual triage.
 */
@Service
@RequiredArgsConstructor
public class DepartmentAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentAssignmentService.class);

    /** History text for the routing step (also used by tests and the UI copy). */
    static final String AWAITING_ASSIGNMENT_SUFFIX = " - awaiting Department Head assignment of a Government Officer";

    private final RoutingRuleRepository routingRuleRepository;
    private final DepartmentRepository departmentRepository;
    private final ComplaintRepository complaintRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.department-assignment.fallback-department-name}")
    private String fallbackDepartmentName;

    /**
     * Routes a VERIFIED complaint to its department and alerts that
     * department's Department Head. The status stays VERIFIED; no officer is
     * chosen here.
     *
     * <p>Audit GAP-059 posture unchanged: the only expected failure - the
     * configured fallback department does not exist - is detected before
     * anything is written and audited; the complaint is left at VERIFIED with
     * no department (an Admin can route it with PATCH .../assign). Data-access
     * failures propagate so the whole AI attempt rolls back and is retried.
     */
    @Transactional
    public void routeToDepartment(Complaint complaint) {
        Department department = resolveDepartmentOrNull(complaint);
        if (department == null) {
            return; // audited in resolveDepartmentOrNull; nothing was written
        }
        complaint.setDepartment(department);
        complaint.setAssignedOfficer(null);
        complaint = complaintRepository.save(complaint);

        recordHistory(complaint, complaint.getStatus(), complaint.getStatus(),
                "Routed to " + department.getName() + AWAITING_ASSIGNMENT_SUFFIX);

        auditService.record(null, "COMPLAINT_ROUTED_TO_DEPARTMENT", "COMPLAINT", complaint.getComplaintId(),
                toJson(Map.of(
                        "department_id", department.getDepartmentId(),
                        "department_name", department.getName())));

        log.info("Complaint {} routed to department {} (awaiting Department Head assignment)",
                complaint.getComplaintId(), department.getName());

        notificationService.notifyDepartmentAssignmentNeeded(complaint);
    }

    /**
     * The routing rule's department, else the fallback department; null (after
     * an audit entry) when even the fallback is missing - a configuration
     * error, detected here without any exception crossing a transactional proxy.
     */
    private Department resolveDepartmentOrNull(Complaint complaint) {
        Optional<Department> department = routingRuleRepository.findCurrentActiveRule(complaint.getCategory())
                .map(RoutingRule::getDepartment)
                .or(() -> departmentRepository.findByNameAndIsActiveTrue(fallbackDepartmentName));
        if (department.isEmpty()) {
            String message = "No active routing rule matched and the configured fallback department ('"
                    + fallbackDepartmentName + "') does not exist - check "
                    + "app.department-assignment.fallback-department-name and the departments table";
            log.error("Department assignment failed for complaint {}: {}", complaint.getComplaintId(), message);
            auditService.record(null, "DEPARTMENT_ASSIGNMENT_FAILED", "COMPLAINT", complaint.getComplaintId(),
                    toJson(Map.of("error", message)));
            return null;
        }
        return department.get();
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
