package com.jannetai.backend.dto.department;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 13 (Department Head Module, SRS 16.2 "Department Performance
 * View": "Monitor officer workload, SLA compliance, and department
 * KPIs" - "UI Components: KPI tiles, officer workload table, SLA
 * compliance chart"). Built by
 * {@code service.department.DepartmentPerformanceService.getPerformance}
 * and returned by {@code GET /api/v1/departments/{id}/performance} - see
 * that endpoint's Javadoc for the exact role scoping
 * (DEPARTMENT_HEAD/ADMIN/SUPER_ADMIN only, a DEPARTMENT_HEAD restricted
 * to their own department).
 */
public record DepartmentPerformanceResponse(
        Long departmentId,
        String departmentName,
        Long totalComplaints,
        Long openComplaints,
        Long resolvedComplaints,
        Long escalatedComplaints,
        Double slaCompliancePercent,
        Double avgResolutionHours,
        List<OfficerWorkloadResponse> officerWorkloads,
        LocalDateTime generatedAt
) {
}
