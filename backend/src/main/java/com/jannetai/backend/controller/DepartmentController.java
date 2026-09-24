package com.jannetai.backend.controller;

import com.jannetai.backend.dto.auth.UserProfileResponse;
import com.jannetai.backend.dto.department.DepartmentPerformanceResponse;
import com.jannetai.backend.dto.department.DepartmentResponse;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.department.DepartmentPerformanceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Phase 11 (SRS 20.4 "Department / Admin APIs": "/api/v1/departments
 * GET ... 200 OK (list of departments)"). Read-only - department
 * creation/editing isn't specified anywhere in the SRS (departments are
 * fixed civic-department seed data, V15) and stays out of scope here.
 * Any authenticated user may list departments (needed by, for example, an
 * Admin building a routing rule, or a citizen-facing screen showing which
 * department a complaint was routed to) - no sensitive data in this
 * response.
 *
 * Phase 13 adds the three nested {@code /{id}/...} endpoints below (SRS
 * 16.2 "Department Performance View (Department Head)"): unlike the
 * unrestricted top-level {@code list()}, every one of these is scoped
 * server-side per {@link DepartmentPerformanceService}'s Javadoc - a
 * DEPARTMENT_HEAD caller can only ever see their own department's data,
 * enforced regardless of what {@code id} they put in the URL (the Phase
 * 13 "IMPORTANT SECURITY" requirement).
 */
@RestController
@RequestMapping("/api/v1/departments")
@RequiredArgsConstructor
@Tag(name = "Departments", description = "Department Assignment Module (Phase 11, SRS 15.7) + Department Head Module (Phase 13, SRS 16.2)")
public class DepartmentController {

    private final DepartmentRepository departmentRepository;
    private final DepartmentPerformanceService departmentPerformanceService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<DepartmentResponse> list() {
        return departmentRepository.findByIsActiveTrueOrderByNameAsc()
                .stream().map(DepartmentResponse::from).toList();
    }

    /**
     * SRS 16.2 "Department Performance View" - officer picker backing
     * the "Reassign Officer" button (the actual reassignment call is
     * Phase 11's {@code PATCH .../complaints/{id}/assign}, reused as-is
     * per the Phase 13 instruction not to duplicate Officer Module
     * functionality).
     */
    @GetMapping("/{id}/officers")
    @PreAuthorize("hasAnyRole('DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public List<UserProfileResponse> officers(@AuthenticationPrincipal UserPrincipal principal,
                                               @PathVariable("id") Long departmentId) {
        return departmentPerformanceService.listOfficers(principal.getUser(), departmentId);
    }

    /**
     * SRS 16.2 "Department Performance View" - KPI tiles, SLA compliance,
     * officer workload table (SRS 24.4's department-level KPIs). See
     * {@link DepartmentPerformanceService#getPerformance} for the exact
     * fields and the SLA-compliance-% formula this project chose.
     */
    @GetMapping("/{id}/performance")
    @PreAuthorize("hasAnyRole('DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public DepartmentPerformanceResponse performance(@AuthenticationPrincipal UserPrincipal principal,
                                                       @PathVariable("id") Long departmentId) {
        return departmentPerformanceService.getPerformance(principal.getUser(), departmentId);
    }

    /**
     * SRS 16.2 "Export Report" button + SRS 21 ("Reports can be exported
     * as PDF or CSV") - CSV only this phase, see
     * {@link DepartmentPerformanceService#exportPerformanceCsv}'s Javadoc
     * for why PDF is out of scope here.
     */
    @GetMapping("/{id}/performance/export")
    @PreAuthorize("hasAnyRole('DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> exportPerformance(@AuthenticationPrincipal UserPrincipal principal,
                                                      @PathVariable("id") Long departmentId) {
        String csv = departmentPerformanceService.exportPerformanceCsv(principal.getUser(), departmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"department-" + departmentId + "-performance.csv\"")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
