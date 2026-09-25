package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Severity;

import java.time.LocalDateTime;

/** Lightweight shape for GET /api/v1/complaints (list/tracking view) - no images or full status history, keep list payloads small. */
public record ComplaintSummaryResponse(
        Long complaintId,
        String referenceNumber,
        ComplaintCategory category,
        String description,
        ComplaintStatus status,
        Severity severity,
        Integer corroborationCount,
        Boolean isEscalated,
        Boolean isReopened,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        /** Audit GAP-040: the persisted SLA deadline (audit GAP-027), for the queue's countdown; null when no clock runs. */
        LocalDateTime slaDueAt
) {
    public static ComplaintSummaryResponse from(Complaint c) {
        return new ComplaintSummaryResponse(
                c.getComplaintId(),
                c.getReferenceNumber(),
                c.getCategory(),
                c.getDescription(),
                c.getStatus(),
                c.getSeverity(),
                c.getCorroborationCount(),
                c.getIsEscalated(),
                c.getIsReopened(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.getSlaDueAt()
        );
    }
}
