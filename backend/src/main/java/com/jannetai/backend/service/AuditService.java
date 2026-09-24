package com.jannetai.backend.service;

import com.jannetai.backend.entity.AuditLog;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Auth-module audit events (SRS 15.2 Dependencies: "Audit Logs"). Uses
 * AuditLogRepository, which is deliberately not delete-capable
 * (AppendOnlyRepository, Phase 4) - see that class's Javadoc.
 *
 * Phase 6 adds an entityType-aware overload (below) so Complaint Module
 * audit events record entityType="COMPLAINT" instead of the original
 * method's hardcoded "USER" - the original 3-arg method is unchanged and
 * still used as-is by AuthService/UserProfileService.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public void record(User actor, String actionType, Long entityId, String detailsJson) {
        record(actor, actionType, "USER", entityId, detailsJson);
    }

    /** Phase 6: entityType-aware variant for modules other than Users (e.g. "COMPLAINT"). */
    public void record(User actor, String actionType, String entityType, Long entityId, String detailsJson) {
        AuditLog log = AuditLog.builder()
                .actor(actor)
                .actionType(actionType)
                .entityType(entityType)
                .entityId(entityId)
                .details(detailsJson)
                .build();
        auditLogRepository.save(log);
    }
}
