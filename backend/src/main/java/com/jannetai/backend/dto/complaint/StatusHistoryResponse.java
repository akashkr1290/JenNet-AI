package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.StatusHistory;
import com.jannetai.backend.entity.enums.ActorType;
import com.jannetai.backend.entity.enums.ComplaintStatus;

import java.time.LocalDateTime;

public record StatusHistoryResponse(
        ComplaintStatus previousStatus,
        ComplaintStatus newStatus,
        ActorType actorType,
        Long actorId,
        String actorName,
        String reason,
        LocalDateTime changedAt
) {
    public static StatusHistoryResponse from(StatusHistory history) {
        return new StatusHistoryResponse(
                history.getPreviousStatus(),
                history.getNewStatus(),
                history.getActorType(),
                history.getActor() != null ? history.getActor().getUserId() : null,
                history.getActor() != null ? history.getActor().getFullName() : null,
                displayReason(history.getReason()),
                history.getChangedAt()
        );
    }

    /** Stored text of pre-GAP-051 rows (status_history is immutable, so the rows are not rewritten). */
    static final String LEGACY_QUEUED_PREFIX = "Queued for AI processing (AI Analysis Module not yet implemented";

    /**
     * Audit GAP-051: complaints created before the fix carry an obsolete
     * developer note in their stored history. The row stays untouched (SRS:
     * immutable status history); only the text shown to users is normalised.
     */
    static String displayReason(String reason) {
        return reason != null && reason.startsWith(LEGACY_QUEUED_PREFIX)
                ? com.jannetai.backend.service.complaint.ComplaintService.QUEUED_FOR_AI_REASON
                : reason;
    }
}
