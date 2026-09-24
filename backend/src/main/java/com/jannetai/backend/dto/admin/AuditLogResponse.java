package com.jannetai.backend.dto.admin;

import com.jannetai.backend.entity.AuditLog;

import java.time.LocalDateTime;

/** GET /api/v1/admin/audit-logs row shape (SRS 15.11 "audit log review"; 16.3 Admin Audit Log screen). */
public record AuditLogResponse(
        Long logId,
        Long actorId,
        String actorName,
        String actionType,
        String entityType,
        Long entityId,
        String details,
        LocalDateTime createdAt
) {
    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
                log.getLogId(),
                log.getActor() != null ? log.getActor().getUserId() : null,
                // Actor is null for system-generated events (e.g. EscalationSchedulerService's
                // sweep, which calls auditService.record(null, ...) - see its own Javadoc).
                log.getActor() != null ? log.getActor().getFullName() : "System",
                log.getActionType(),
                log.getEntityType(),
                log.getEntityId(),
                log.getDetails(),
                log.getCreatedAt()
        );
    }
}
