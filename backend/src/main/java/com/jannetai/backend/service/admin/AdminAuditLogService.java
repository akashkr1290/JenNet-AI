package com.jannetai.backend.service.admin;

import com.jannetai.backend.dto.admin.AuditLogResponse;
import com.jannetai.backend.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Phase 14 (Admin & Settings Module, SRS 15.11 "audit log review"; 16.3
 * Admin Audit Log screen). Read-only - see AuditLogRepository's own
 * Javadoc for why this trail can never be altered through the
 * application layer.
 */
@Service
@RequiredArgsConstructor
public class AdminAuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> list(Long actorId, String entityType, String actionType,
                                        LocalDateTime from, LocalDateTime to, int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(pageSize, 1), 100));
        return auditLogRepository.search(actorId, entityType, actionType, from, to, pageable)
                .map(AuditLogResponse::from);
    }
}
