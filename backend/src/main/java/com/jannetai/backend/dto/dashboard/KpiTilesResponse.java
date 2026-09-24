package com.jannetai.backend.dto.dashboard;

/**
 * Phase 16 (Government Dashboard Module, SRS 15.10 Features: "KPI tiles
 * (open, resolved, average resolution time, SLA compliance)"; SRS 16.3
 * "KPI tiles (open/resolved/avg. resolution time/SLA compliance)").
 * Reuses the exact same SLA-compliance-% formula and average-resolution-
 * time computation {@code DepartmentPerformanceService} established in
 * Phase 13 ({@code (total - escalated) / total * 100}, in-Java mean of
 * {@code updatedAt - createdAt} over RESOLVED/CLOSED complaints) - see
 * {@code AnalyticsAggregationService} for where this is computed at
 * whatever scope (single department or the whole jurisdiction) the
 * caller requested.
 */
public record KpiTilesResponse(
        Long totalComplaints,
        Long openComplaints,
        Long resolvedComplaints,
        Long escalatedComplaints,
        Double slaCompliancePercent,
        Double avgResolutionHours
) {
}
