package com.jannetai.backend.controller;

import com.jannetai.backend.dto.dashboard.AdminDashboardSummaryResponse;
import com.jannetai.backend.dto.dashboard.DepartmentComparisonResponse;
import com.jannetai.backend.dto.dashboard.GovernmentDashboardResponse;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.dashboard.GovernmentDashboardService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Phase 16 (Government Dashboard Module, SRS 15.10; Analytics Module,
 * SRS 15.14). See {@link GovernmentDashboardService}'s class Javadoc for
 * the full role-scoping rationale - summarized per endpoint below.
 *
 * DRILL-DOWN (SRS 15.10 Actions: "drill down into any KPI to underlying
 * complaint list"; SRS 16.3 same wording): deliberately NOT a new
 * endpoint here. The existing staff-facing complaint listing
 * (Phase 6/12, {@code GET /api/v1/complaints} with
 * {@code status}/{@code category}/{@code departmentId} query params,
 * already scoped server-side per role) is exactly the "underlying
 * complaint list" a KPI tile or heatmap ward drills down into - the
 * Flutter dashboard screen navigates there with the relevant filter
 * pre-applied rather than this module duplicating that listing logic.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@Tag(name = "Government Dashboard", description = "Government/Admin Dashboard & Analytics Module (Phase 16, SRS 15.10/15.14)")
public class GovernmentDashboardController {

    private final GovernmentDashboardService governmentDashboardService;

    /**
     * SRS 16.3 "Government Dashboard (Overview)" - KPI tiles, heatmap,
     * category trend. {@code departmentId} is optional: omitted (or
     * null) means "my own department" for a DEPARTMENT_HEAD, or
     * "jurisdiction-wide" for ADMIN/SUPER_ADMIN (who may also pass a
     * specific department id). GOVERNMENT_OFFICER is intentionally not
     * included in this permission set - see
     * {@link GovernmentDashboardService}'s class Javadoc.
     */
    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public GovernmentDashboardResponse overview(@AuthenticationPrincipal UserPrincipal principal,
                                                 @RequestParam(name = "departmentId", required = false) Long departmentId) {
        return governmentDashboardService.getOverview(principal.getUser(), departmentId);
    }

    /**
     * SRS 15.10/24.3/24.4 "department comparison chart" - jurisdiction-
     * wide, ADMIN/SUPER_ADMIN only (see
     * {@link GovernmentDashboardService#getDepartmentComparison}'s
     * Javadoc for why DEPARTMENT_HEAD never reaches this endpoint at
     * all).
     */
    @GetMapping("/department-comparison")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public List<DepartmentComparisonResponse> departmentComparison() {
        return governmentDashboardService.getDepartmentComparison();
    }

    /**
     * SRS 24.3 "Admin Dashboard" - platform-wide KPIs/charts/statistics.
     * ADMIN/SUPER_ADMIN only.
     */
    @GetMapping("/admin-summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public AdminDashboardSummaryResponse adminSummary() {
        return governmentDashboardService.getAdminSummary();
    }

    /**
     * SRS 15.10 Outputs: "exportable report snapshots" / SRS 21
     * ("Reports can be exported as PDF or CSV") - CSV only this phase,
     * see {@link GovernmentDashboardService#exportOverviewCsv}'s Javadoc
     * for why. Same role scoping as {@link #overview}; when
     * jurisdiction-wide (ADMIN/SUPER_ADMIN, no departmentId), the export
     * additionally includes the Admin summary section.
     */
    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal UserPrincipal principal,
                                          @RequestParam(name = "departmentId", required = false) Long departmentId) {
        String csv = governmentDashboardService.exportOverviewCsv(principal.getUser(), departmentId);
        String filename = departmentId == null ? "government-dashboard-jurisdiction.csv"
                : "government-dashboard-department-" + departmentId + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
