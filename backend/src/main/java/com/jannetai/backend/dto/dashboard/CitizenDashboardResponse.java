package com.jannetai.backend.dto.dashboard;

import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Severity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Gap-backlog Patch 08 (Sep 2026 strict recheck): the citizen's own dashboard.
 * "pending" = SUBMITTED + AI_PROCESSING + VERIFIED + ASSIGNED (accepted but
 * work not yet started); every count comes from a real GROUP BY over the
 * citizen's own complaints, never estimated.
 */
public record CitizenDashboardResponse(
        long total,
        long pending,
        long inProgress,
        long resolved,
        long closed,
        long rejected,
        long duplicate,
        List<RecentComplaint> recentComplaints
) {
    /** "Priority" is the system's own severity + priority score (Prediction.priorityScore); null until predicted. */
    public record RecentComplaint(
            Long complaintId,
            String referenceNumber,
            ComplaintCategory category,
            ComplaintStatus status,
            Severity severity,
            BigDecimal priorityScore,
            String aiStatus,
            BigDecimal aiConfidence,
            LocalDateTime createdAt,
            LocalDateTime slaDueAt,
            boolean slaBreached
    ) {
    }
}
