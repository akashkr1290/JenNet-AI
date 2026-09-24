package com.jannetai.backend.service.dashboard;

import com.jannetai.backend.dto.dashboard.AdminDashboardSummaryResponse;
import com.jannetai.backend.dto.dashboard.CategoryTrendPointResponse;
import com.jannetai.backend.dto.dashboard.DepartmentComparisonResponse;
import com.jannetai.backend.dto.dashboard.GovernmentDashboardResponse;
import com.jannetai.backend.dto.dashboard.RoutingRuleEffectivenessResponse;
import com.jannetai.backend.dto.dashboard.WardHeatmapPointResponse;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.service.dashboard.AnalyticsCacheService.DashboardSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Phase 16 (Government Dashboard Module, SRS 15.10 / 16.3 "Government
 * Dashboard (Overview)" screen / 24.3 "Admin Dashboard" / 24.4
 * "Government Dashboard (Department Head / Jurisdiction level)"). Owns
 * role-scoping and response assembly on top of
 * {@link AnalyticsCacheService}'s cached snapshots - the same
 * responsibility split Phase 13 established between
 * {@code DepartmentController} (thin) and
 * {@code DepartmentPerformanceService} (scoping + assembly), just with
 * an extra caching layer underneath this phase (Phase 13's service
 * queried live on every call - see {@code AnalyticsCacheService}'s class
 * Javadoc for why Phase 16 doesn't).
 *
 * ROLE SCOPING (SRS 15.10 Business Rules: "Officer sees own department/
 * zone; Department Head sees whole department; Admin sees whole
 * jurisdiction; Super Admin sees all jurisdictions"; SRS 16.3
 * "Government Dashboard (Overview)" screen's own, narrower permission
 * line: "Permissions: Department Head, Admin, Super Admin"):
 * <ul>
 *   <li>GOVERNMENT_OFFICER is deliberately NOT given access to this
 *       screen's endpoints - the screen-level permission line (16.3) is
 *       treated as authoritative over the more general 15.10 business-
 *       rule text, since it is the more specific of the two SRS
 *       statements about the exact same screen. An Officer's own
 *       dashboard needs (SRS 24.2 "Officer Dashboard": queue breakdown,
 *       personal-vs-department performance) are a distinct, narrower
 *       screen this codebase does not yet build - out of Phase 16's
 *       scope per its own title ("Government/Admin Dashboard &amp;
 *       Analytics"), not silently folded into this endpoint. Documented
 *       in PROJECT_INTEGRATION.md Section 6 rather than silently
 *       resolved.</li>
 *   <li>DEPARTMENT_HEAD is always forced to their own department -
 *       identical fail-closed-on-mismatch behavior to Phase 13's
 *       {@code DepartmentPerformanceService.requireScopedDepartmentId}
 *       (reused verbatim below, not re-derived).</li>
 *   <li>ADMIN/SUPER_ADMIN may pass {@code departmentId = null} (a
 *       jurisdiction-wide view) or any specific department - SRS 15.10
 *       treats them identically ("Admin sees whole jurisdiction; Super
 *       Admin sees all jurisdictions" - this schema models a single
 *       jurisdiction; see {@code ComplaintRepository}'s Phase 16 section
 *       Javadoc).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class GovernmentDashboardService {

    private final AnalyticsCacheService analyticsCacheService;

    /**
     * SRS 16.3 "Government Dashboard (Overview)" - KPI tiles, heatmap,
     * category trend. See class Javadoc for the exact role-scoping this
     * enforces before ever touching the cache.
     */
    public GovernmentDashboardResponse getOverview(User requester, Long requestedDepartmentId) {
        Long scopedDepartmentId = resolveScopedDepartmentId(requester, requestedDepartmentId);
        DashboardSnapshot snapshot = analyticsCacheService.getSnapshot(scopedDepartmentId);
        return toResponse(snapshot);
    }

    /**
     * SRS 15.10/24.3/24.4 "department comparison chart" - jurisdiction-
     * wide only, ADMIN/SUPER_ADMIN only (a cross-department comparison
     * makes no sense scoped to one department, so DEPARTMENT_HEAD is
     * never given a filtered version of it - the controller's
     * {@code @PreAuthorize} enforces this before this method is ever
     * reached, same defense-in-depth precedent as every other role-
     * restricted method in this codebase).
     */
    public List<DepartmentComparisonResponse> getDepartmentComparison() {
        return analyticsCacheService.getDepartmentComparison();
    }

    /**
     * SRS 24.3 "Admin Dashboard" - platform-wide summary. ADMIN/
     * SUPER_ADMIN only (controller-enforced).
     */
    public AdminDashboardSummaryResponse getAdminSummary() {
        return analyticsCacheService.getAdminSummary();
    }

    /**
     * SRS 15.10 Outputs: "exportable report snapshots" / SRS 21
     * ("Reports can be exported as PDF or CSV"). CSV only, same
     * hand-rolled-writer precedent and same "PDF/scheduled-email is the
     * separate, not-yet-phased Reports Module's job" scope decision as
     * Phase 13's {@code DepartmentPerformanceService.exportPerformanceCsv}
     * - see that method's Javadoc for the full reasoning, unchanged here.
     */
    public String exportOverviewCsv(User requester, Long requestedDepartmentId) {
        Long scopedDepartmentId = resolveScopedDepartmentId(requester, requestedDepartmentId);
        GovernmentDashboardResponse overview = getOverview(requester, scopedDepartmentId);

        StringBuilder sb = new StringBuilder();
        sb.append("Government Dashboard Snapshot\n");
        sb.append("Scope,").append(scopedDepartmentId == null ? "Jurisdiction-wide" : "Department " + scopedDepartmentId)
                .append('\n');
        sb.append("Data As Of,").append(overview.dataAsOf()).append('\n');
        sb.append('\n');
        sb.append("Total Complaints,").append(overview.kpis().totalComplaints()).append('\n');
        sb.append("Open Complaints,").append(overview.kpis().openComplaints()).append('\n');
        sb.append("Resolved Complaints,").append(overview.kpis().resolvedComplaints()).append('\n');
        sb.append("Escalated Complaints,").append(overview.kpis().escalatedComplaints()).append('\n');
        sb.append("SLA Compliance %,").append(overview.kpis().slaCompliancePercent()).append('\n');
        sb.append("Avg Resolution Hours,")
                .append(overview.kpis().avgResolutionHours() == null ? "N/A" : overview.kpis().avgResolutionHours())
                .append('\n');
        sb.append('\n');
        sb.append("Ward Heatmap\n");
        sb.append("Ward ID,Ward Name,Complaint Count,Open Count\n");
        for (WardHeatmapPointResponse w : overview.heatmap()) {
            sb.append(w.wardId()).append(',').append(csv(w.wardName())).append(',')
                    .append(w.complaintCount()).append(',').append(w.openComplaintCount()).append('\n');
        }
        sb.append('\n');
        sb.append("Category Trend (last 90 days, daily buckets)\n");
        sb.append("Date,Category,Count\n");
        for (CategoryTrendPointResponse p : overview.categoryTrend()) {
            sb.append(p.bucketDate()).append(',').append(p.category()).append(',').append(p.count()).append('\n');
        }

        if (scopedDepartmentId == null) {
            appendAdminSummaryCsv(sb);
        }
        return sb.toString();
    }

    private void appendAdminSummaryCsv(StringBuilder sb) {
        AdminDashboardSummaryResponse admin = getAdminSummary();
        sb.append('\n');
        sb.append("Admin Summary\n");
        sb.append("Active Users,").append(admin.activeUserCount()).append('\n');
        sb.append("Total Users,").append(admin.totalUserCount()).append('\n');
        sb.append("AI Auto-Processed,").append(admin.aiAutoProcessedCount()).append('\n');
        sb.append("Manually Processed,").append(admin.manuallyProcessedCount()).append('\n');
        sb.append("AI Auto-Processing Rate %,")
                .append(admin.aiAutoProcessingRatePercent() == null ? "N/A" : admin.aiAutoProcessingRatePercent())
                .append('\n');
        sb.append("Duplicate-Merge Rate %,").append(admin.duplicateMergeRatePercent()).append('\n');
        sb.append("Configuration Changes (last 30 days),")
                .append(admin.configurationChangeCountLast30Days()).append('\n');
        sb.append("Notification Failure Rate % (last 30 days),")
                .append(admin.notificationFailureRatePercent()).append('\n');
        sb.append('\n');
        sb.append("Routing Rule Effectiveness\n");
        sb.append("Routing Rule ID,Category,Department,Complaint Count,SLA Compliance %\n");
        for (RoutingRuleEffectivenessResponse r : admin.routingRuleEffectiveness()) {
            sb.append(r.routingRuleId()).append(',').append(r.category()).append(',')
                    .append(csv(r.departmentName())).append(',').append(r.complaintCount()).append(',')
                    .append(r.slaCompliancePercent()).append('\n');
        }
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

    private GovernmentDashboardResponse toResponse(DashboardSnapshot snapshot) {
        return new GovernmentDashboardResponse(
                snapshot.kpis(), snapshot.heatmap(), snapshot.categoryTrend(), snapshot.generatedAt());
    }

    /**
     * Identical fail-closed-on-mismatch scoping rule as Phase 13's
     * {@code DepartmentPerformanceService.requireScopedDepartmentId},
     * with one difference: {@code null} is a legal, meaningful value
     * here for ADMIN/SUPER_ADMIN (jurisdiction-wide), whereas that Phase
     * 13 method requires a concrete department for every caller (the
     * Department Performance View has no jurisdiction-wide mode at all).
     */
    private Long resolveScopedDepartmentId(User requester, Long requestedDepartmentId) {
        if (requester.getRole() == Role.DEPARTMENT_HEAD) {
            Long ownDepartmentId = requester.getDepartment() != null
                    ? requester.getDepartment().getDepartmentId() : null;
            if (ownDepartmentId == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "No department is assigned to this account");
            }
            // Unlike Phase 13's identically-named method (which requires an
            // explicit, matching departmentId from every caller), a null
            // requestedDepartmentId here is treated as "use my own
            // department" rather than an error - this is inherently a
            // single-department-scoped screen for this role, so there is
            // no ambiguity to reject. An explicit, MISMATCHED departmentId
            // is still a 403, not a silent substitution (same fail-closed
            // principle as Phase 13).
            if (requestedDepartmentId != null && !requestedDepartmentId.equals(ownDepartmentId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You may only view your own department's dashboard");
            }
            return ownDepartmentId;
        }
        // ADMIN/SUPER_ADMIN: departmentId is optional (null = jurisdiction-wide).
        return requestedDepartmentId;
    }
}
