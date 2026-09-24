package com.jannetai.backend.dto.department;

/**
 * Phase 13 (Department Head Module, SRS 16.2 "Department Performance
 * View" - "officer workload table"; SRS 24.4 "officer workload
 * distribution"). One row per {@link com.jannetai.backend.entity.User}
 * with role GOVERNMENT_OFFICER in the department, built by
 * {@code service.department.DepartmentPerformanceService}.
 */
public record OfficerWorkloadResponse(
        Long officerId,
        String officerName,
        Long assignedOpenCount,
        Long resolvedCount,
        Double avgResolutionHours,
        Long slaBreachCount
) {
}
