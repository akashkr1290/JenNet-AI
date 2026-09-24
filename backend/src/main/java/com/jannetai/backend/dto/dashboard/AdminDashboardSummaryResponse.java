package com.jannetai.backend.dto.dashboard;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 16 (Analytics Module + Admin Module, SRS 24.3 "Admin Dashboard":
 * "KPIs: platform-wide open/resolved counts, active users, AI
 * auto-processing rate versus manual verification rate. Charts:
 * department comparison, routing-rule effectiveness, duplicate-merge
 * rate. Statistics: configuration change audit summary, system health
 * indicators"). Backs {@code GET /api/v1/dashboard/admin-summary} -
 * ADMIN/SUPER_ADMIN only (no scoping parameter - this is inherently a
 * platform-wide view, see {@code GovernmentDashboardService#getAdminSummary}'s
 * Javadoc).
 *
 * "Department comparison" is deliberately NOT duplicated onto this DTO -
 * it is its own endpoint/response ({@link DepartmentComparisonResponse}
 * via {@code GET /api/v1/dashboard/department-comparison}) so a caller
 * that only needs the chart data doesn't have to also pay for this
 * summary's other aggregations, and vice versa.
 *
 * SYSTEM HEALTH INDICATORS: SRS Section 20's "Infrastructure and
 * application metrics (CPU, memory, request latency, error rate, queue
 * depth)... visualized on an operations dashboard" describes a distinct,
 * infrastructure-level Monitoring capability with no phase of its own in
 * ARCHITECTURE.md's Phase -&gt; Component map (closest candidates are
 * Phase 18 docker/ or Phase 22 deployment/, neither of which exists yet)
 * - out of scope here, not silently reinterpreted. What this DTO reports
 * as "system health" instead is the operational health data this backend
 * already has real numbers for: notification delivery failure rate
 * (Phase 15's {@code Notification.deliveryStatus}) and the count of
 * complaints currently sitting in an unresolved SLA-breach state. See
 * PROJECT_INTEGRATION.md Section 6 for the full decision record.
 */
public record AdminDashboardSummaryResponse(
        Long activeUserCount,
        Long totalUserCount,
        Long totalComplaints,
        Long openComplaints,
        Long resolvedComplaints,
        Long aiAutoProcessedCount,
        Long manuallyProcessedCount,
        Double aiAutoProcessingRatePercent,
        Long duplicateComplaintCount,
        Double duplicateMergeRatePercent,
        List<RoutingRuleEffectivenessResponse> routingRuleEffectiveness,
        Long configurationChangeCountLast30Days,
        Long notificationFailureCountLast30Days,
        Double notificationFailureRatePercent,
        Long currentlyEscalatedComplaints,
        LocalDateTime dataAsOf
) {
}
