package com.jannetai.backend.dto.dashboard;

import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Severity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Gap-backlog Patch 09 (Sep 2026 strict recheck): an officer's own dashboard.
 * SLA buckets apply to OPEN work (ASSIGNED/IN_PROGRESS) only: overdue = past
 * due; atRisk = at least 80% of the SLA window elapsed (the same 80% cut-off
 * the existing SLA-warning sweep uses); onTrack = everything else with a due
 * time; noSla = no severity yet, so no due time can honestly be computed.
 * Personal performance covers this officer's own RESOLVED transitions in the
 * last 30 days, read from status_history (who actually resolved it, when).
 */
public record OfficerDashboardResponse(
        long assigned,
        long inProgress,
        long resolved,
        long closed,
        long overdue,
        long atRisk,
        long onTrack,
        long noSla,
        long resolvedLast30Days,
        Double avgResolutionHoursLast30Days,
        Double slaCompliancePercentLast30Days,
        List<TaskItem> todaysTasks
) {
    public record TaskItem(
            Long complaintId,
            String referenceNumber,
            ComplaintCategory category,
            ComplaintStatus status,
            Severity severity,
            LocalDateTime slaDueAt,
            boolean overdue
    ) {
    }
}
