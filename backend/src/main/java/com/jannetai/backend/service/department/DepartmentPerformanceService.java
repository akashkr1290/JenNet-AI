package com.jannetai.backend.service.department;

import com.jannetai.backend.dto.auth.UserProfileResponse;
import com.jannetai.backend.dto.department.DepartmentPerformanceResponse;
import com.jannetai.backend.dto.department.OfficerWorkloadResponse;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Phase 13 (Department Head Module, SRS 16.2 "Department Performance
 * View" + SRS 24.4 "Government Dashboard (Department Head / Jurisdiction
 * level)"). Deliberately narrower than the SRS 15.10/16.3 Government
 * Dashboard module (heatmaps, cross-department comparison charts,
 * jurisdiction-wide rollups for Admin/Super Admin) - that broader,
 * multi-role dashboard is Phase 16's job per ARCHITECTURE.md Section 8's
 * Phase -> Component map. This service only builds the one screen SRS
 * 16.2 places under the Department Head's own permissions (line
 * "Permissions: Department Head, Admin, Super Admin"): a single
 * department's KPIs, SLA compliance, and officer workload breakdown.
 *
 * SCOPING (the Phase 13 "IMPORTANT SECURITY" requirement): every method
 * here takes the requester and re-derives which department they're
 * actually allowed to see - a DEPARTMENT_HEAD can only ever pass their
 * own department_id (any other value is rejected with 403, not silently
 * substituted), matching the same "scoped server-side, not merely hidden
 * client-side" principle {@code ComplaintService.list}/
 * {@code requireCanView} already enforce for the complaint queue (Phase
 * 12). ADMIN/SUPER_ADMIN may view any department - the SRS 16.2
 * permission line names them alongside Department Head with no
 * department restriction described for those two roles.
 *
 * SLA-COMPLIANCE-% FORMULA (a genuine SRS gap, documented rather than
 * silently picked - see PROJECT_INTEGRATION.md Section 6): the SRS
 * describes an "SLA compliance chart" (16.2) and "SLA compliance" KPI
 * (15.10, 24.4) but never specifies the exact formula. This computes it
 * as {@code (totalComplaints - escalatedComplaints) / totalComplaints *
 * 100} over the department's all-time complaint count, reusing the
 * existing {@code complaints.is_escalated} flag
 * {@link EscalationSchedulerService} already sets on an SLA breach
 * (Phase 11) rather than introducing a new per-complaint SLA-tracking
 * column - a complaint that was never escalated is treated as SLA-
 * compliant. A department with zero complaints is reported as 100%
 * compliant (nothing has ever breached), not a division-by-zero error.
 *
 * AVERAGE RESOLUTION TIME: computed in Java as the mean of
 * {@code updatedAt - createdAt} across every RESOLVED/CLOSED complaint
 * (department-wide, or per officer for the workload table) rather than a
 * SQL AVG(TIMESTAMPDIFF(...)) native query - this project's stated
 * preference for the simplest thing that satisfies the SRS
 * (ARCHITECTURE.md Section 7), same as every other aggregate here. A
 * KNOWN LIMITATION worth flagging for a future phase if a department's
 * resolved-complaint volume grows large: this loads every matching
 * Complaint row into memory rather than aggregating in the database:
 * acceptable at this project's current/expected civic-complaint scale,
 * not necessarily at a much larger one.
 */
@Service
@RequiredArgsConstructor
public class DepartmentPerformanceService {

    private static final Set<ComplaintStatus> OPEN_STATUSES = Set.of(
            ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS);
    private static final Set<ComplaintStatus> RESOLVED_STATUSES = Set.of(
            ComplaintStatus.RESOLVED, ComplaintStatus.CLOSED);

    private final ComplaintRepository complaintRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;

    /**
     * SRS 16.2 "Department Performance View" - Buttons: "Reassign
     * Officer" (this list is what populates the officer picker; the
     * actual reassignment reuses Phase 11's
     * {@code PATCH .../complaints/{id}/assign}, not duplicated here per
     * the Phase 13 instruction not to duplicate existing Officer Module
     * functionality).
     */
    @Transactional(readOnly = true)
    public List<UserProfileResponse> listOfficers(User requester, Long departmentId) {
        Long scopedDepartmentId = requireScopedDepartmentId(requester, departmentId);
        return userRepository.findByRoleAndDepartment_DepartmentIdOrderByFullNameAsc(
                        Role.GOVERNMENT_OFFICER, scopedDepartmentId)
                .stream().map(UserProfileResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public DepartmentPerformanceResponse getPerformance(User requester, Long departmentId) {
        Long scopedDepartmentId = requireScopedDepartmentId(requester, departmentId);
        Department department = departmentRepository.findById(scopedDepartmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + scopedDepartmentId));

        long total = complaintRepository.countByDepartment_DepartmentId(scopedDepartmentId);
        long open = complaintRepository.countByDepartment_DepartmentIdAndStatusIn(scopedDepartmentId, OPEN_STATUSES);
        long resolved = complaintRepository.countByDepartment_DepartmentIdAndStatusIn(
                scopedDepartmentId, RESOLVED_STATUSES);
        long escalated = complaintRepository.countByDepartment_DepartmentIdAndIsEscalated(scopedDepartmentId, true);
        Double slaCompliancePercent = total == 0 ? 100.0 : (double) (total - escalated) / total * 100.0;

        List<Complaint> resolvedComplaints = complaintRepository.findByDepartment_DepartmentIdAndStatusIn(
                scopedDepartmentId, RESOLVED_STATUSES);
        Double avgResolutionHours = averageResolutionHours(resolvedComplaints);

        List<User> officers = userRepository.findByRoleAndDepartment_DepartmentIdOrderByFullNameAsc(
                Role.GOVERNMENT_OFFICER, scopedDepartmentId);
        List<OfficerWorkloadResponse> workloads = officers.stream()
                .map(this::officerWorkload)
                .toList();

        return new DepartmentPerformanceResponse(
                department.getDepartmentId(),
                department.getName(),
                total,
                open,
                resolved,
                escalated,
                round1(slaCompliancePercent),
                round1(avgResolutionHours),
                workloads,
                LocalDateTime.now());
    }

    private OfficerWorkloadResponse officerWorkload(User officer) {
        Long officerId = officer.getUserId();
        long assignedOpen = complaintRepository.countByAssignedOfficer_UserIdAndStatusIn(officerId, OPEN_STATUSES);
        long resolved = complaintRepository.countByAssignedOfficer_UserIdAndStatusIn(officerId, RESOLVED_STATUSES);
        long slaBreaches = complaintRepository.countByAssignedOfficer_UserIdAndIsEscalated(officerId, true);
        List<Complaint> resolvedComplaints = complaintRepository.findByAssignedOfficer_UserIdAndStatusIn(
                officerId, RESOLVED_STATUSES);
        Double avgResolutionHours = round1(averageResolutionHours(resolvedComplaints));

        return new OfficerWorkloadResponse(
                officerId, officer.getFullName(), assignedOpen, resolved, avgResolutionHours, slaBreaches);
    }

    private Double averageResolutionHours(List<Complaint> resolvedOrClosed) {
        if (resolvedOrClosed.isEmpty()) {
            return null; // no completed complaints yet - nothing to average, not zero (a false "instant resolution" signal)
        }
        double totalHours = 0;
        for (Complaint c : resolvedOrClosed) {
            if (c.getCreatedAt() != null && c.getUpdatedAt() != null) {
                totalHours += Duration.between(c.getCreatedAt(), c.getUpdatedAt()).toMinutes() / 60.0;
            }
        }
        return totalHours / resolvedOrClosed.size();
    }

    private static Double round1(Double value) {
        return value == null ? null : Math.round(value * 10.0) / 10.0;
    }

    /**
     * SRS 21 ("Reports can be exported as PDF or CSV"). CSV only this
     * phase - PDF generation is a formatting/rendering concern shared
     * with the full Reports Module (Phase 21 per ARCHITECTURE.md's Phase
     * -> Component map: "20 | all - testing", "21 | .github/workflows/"
     * doesn't even own Reports; there is no dedicated Reports-module
     * phase row yet - deliberately out of scope here rather than
     * guessed at). Hand-rolled CSV writer, no new dependency - consistent
     * with this class/module's existing hand-rolled-JSON precedent
     * ({@code ComplaintService.escapeJson}) and this environment's no-
     * outbound-network constraint (no Maven Central access to pull in a
     * CSV library even if one were wanted).
     */
    @Transactional(readOnly = true)
    public String exportPerformanceCsv(User requester, Long departmentId) {
        DepartmentPerformanceResponse perf = getPerformance(requester, departmentId);
        StringBuilder sb = new StringBuilder();
        sb.append("Department Performance Report\n");
        sb.append("Department,").append(csv(perf.departmentName())).append('\n');
        sb.append("Generated At,").append(perf.generatedAt()).append('\n');
        sb.append('\n');
        sb.append("Total Complaints,").append(perf.totalComplaints()).append('\n');
        sb.append("Open Complaints,").append(perf.openComplaints()).append('\n');
        sb.append("Resolved Complaints,").append(perf.resolvedComplaints()).append('\n');
        sb.append("Escalated Complaints,").append(perf.escalatedComplaints()).append('\n');
        sb.append("SLA Compliance %,").append(perf.slaCompliancePercent()).append('\n');
        sb.append("Avg Resolution Hours,")
                .append(perf.avgResolutionHours() == null ? "N/A" : perf.avgResolutionHours()).append('\n');
        sb.append('\n');
        sb.append("Officer Workload\n");
        sb.append("Officer ID,Officer Name,Assigned Open,Resolved,Avg Resolution Hours,SLA Breaches\n");
        for (OfficerWorkloadResponse w : perf.officerWorkloads()) {
            sb.append(w.officerId()).append(',')
                    .append(csv(w.officerName())).append(',')
                    .append(w.assignedOpenCount()).append(',')
                    .append(w.resolvedCount()).append(',')
                    .append(w.avgResolutionHours() == null ? "N/A" : w.avgResolutionHours()).append(',')
                    .append(w.slaBreachCount()).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * The Phase 13 "IMPORTANT SECURITY" requirement in one place: a
     * DEPARTMENT_HEAD may only ever be scoped to their own department -
     * any caller-supplied {@code departmentId} that doesn't match their
     * own is a 403, not a silent substitution (silently substituting
     * their own department for a mismatched request would hide the
     * caller's mistake; failing closed is the same "fail closed on a
     * scope mismatch" behavior {@code ComplaintService.requireCanView}
     * established in Phase 12). ADMIN/SUPER_ADMIN pass through
     * unrestricted - the SRS 16.2 permission line for this screen names
     * them with no department restriction. Every other role can never
     * reach this method at all (the controller's {@code @PreAuthorize}
     * only allows DEPARTMENT_HEAD/ADMIN/SUPER_ADMIN to call these
     * endpoints in the first place).
     */
    private Long requireScopedDepartmentId(User requester, Long requestedDepartmentId) {
        if (requester.getRole() == Role.DEPARTMENT_HEAD) {
            Long ownDepartmentId = requester.getDepartment() != null
                    ? requester.getDepartment().getDepartmentId() : null;
            if (ownDepartmentId == null || !ownDepartmentId.equals(requestedDepartmentId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You may only view your own department's data");
            }
            return ownDepartmentId;
        }
        // ADMIN/SUPER_ADMIN: unrestricted, but the department must still exist.
        if (requestedDepartmentId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "departmentId is required");
        }
        return requestedDepartmentId;
    }
}
