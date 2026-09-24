package com.jannetai.backend.dto.dashboard;

/**
 * Phase 16 (Government Dashboard Module, SRS 15.10 UI Components:
 * "department comparison chart"; SRS 24.3 Admin Dashboard Charts:
 * "department comparison"). One row per active department, jurisdiction-
 * wide (never department-scoped - a cross-department comparison is
 * meaningless scoped to a single department) - ADMIN/SUPER_ADMIN only,
 * see {@code GovernmentDashboardService#getDepartmentComparison}'s
 * Javadoc for the exact role restriction and why a DEPARTMENT_HEAD never
 * reaches this endpoint at all rather than receiving a filtered view of
 * it.
 */
public record DepartmentComparisonResponse(
        Long departmentId,
        String departmentName,
        Long totalComplaints,
        Long openComplaints,
        Long resolvedComplaints,
        Double slaCompliancePercent
) {
}
