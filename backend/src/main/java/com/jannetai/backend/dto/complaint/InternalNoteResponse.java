package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.AuditLog;

import java.time.LocalDateTime;

/**
 * Phase 12 (SRS 16.2 "Add Internal Note") - read shape for the AuditLog
 * rows ComplaintService.addInternalNote writes. Deliberately not the
 * literal JSON stored in {@code details} - {@code note} is unescaped back
 * out of it (see ComplaintService.toResponse's mapping) so the API
 * consumer never has to parse a nested JSON string themselves.
 */
public record InternalNoteResponse(
        Long logId,
        Long authorUserId,
        String authorName,
        String note,
        LocalDateTime createdAt
) {
    public static InternalNoteResponse from(AuditLog log, String note) {
        return new InternalNoteResponse(
                log.getLogId(),
                log.getActor() != null ? log.getActor().getUserId() : null,
                log.getActor() != null ? log.getActor().getFullName() : null,
                note,
                log.getCreatedAt()
        );
    }
}
