package com.jannetai.backend.controller;

import com.jannetai.backend.dto.admin.AuditLogResponse;
import com.jannetai.backend.service.admin.AdminAuditLogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * Phase 14 (Admin & Settings Module, SRS 15.11 "audit log review"; 16.3
 * Admin Audit Log screen). ADMIN/SUPER_ADMIN only - Security 27.4's
 * append-only guarantee is enforced by AuditLogRepository/AuditService,
 * not here; this controller is read-only by construction (no POST/PATCH/
 * DELETE mapping exists on it at all).
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@RequiredArgsConstructor
@Tag(name = "Admin Audit Log", description = "Append-only administrative audit trail review (Phase 14, SRS 15.11)")
public class AdminAuditLogController {

    private final AdminAuditLogService adminAuditLogService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public Page<AuditLogResponse> list(@RequestParam(required = false) Long actorId,
                                        @RequestParam(required = false) String entityType,
                                        @RequestParam(required = false) String actionType,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int pageSize) {
        return adminAuditLogService.list(actorId, entityType, actionType, from, to, page, pageSize);
    }
}
