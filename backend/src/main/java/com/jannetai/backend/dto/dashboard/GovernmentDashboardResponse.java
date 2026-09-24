package com.jannetai.backend.dto.dashboard;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 16 (Government Dashboard Module, SRS 15.10 "Government Dashboard"
 * / SRS 16.3 "Government Dashboard (Overview)" screen / SRS 24.4
 * "Government Dashboard (Department Head / Jurisdiction level)"). Backs
 * {@code GET /api/v1/dashboard/overview} - see
 * {@code GovernmentDashboardService#getOverview}'s Javadoc for the exact
 * role-based scoping (a DEPARTMENT_HEAD is always forced to their own
 * department; ADMIN/SUPER_ADMIN may pass {@code departmentId=null} for a
 * jurisdiction-wide view or any specific department).
 *
 * {@code dataAsOf} is SRS 15.10's Exceptions clause made concrete: "if
 * real-time aggregation service is degraded, the dashboard falls back to
 * the last successfully cached aggregate with a visible 'data as of
 * [timestamp]' notice" - every response here is served from
 * {@code AnalyticsCacheService}'s cache (refreshed nightly per SRS 15.14
 * Business Rules, "default nightly... to protect performance"), so this
 * field is always populated and always means exactly that: when this
 * snapshot was actually computed, not necessarily "just now". See
 * {@code AnalyticsCacheService}'s class Javadoc for the full caching
 * design and its fallback-on-refresh-failure behavior.
 */
public record GovernmentDashboardResponse(
        KpiTilesResponse kpis,
        List<WardHeatmapPointResponse> heatmap,
        List<CategoryTrendPointResponse> categoryTrend,
        LocalDateTime dataAsOf
) {
}
