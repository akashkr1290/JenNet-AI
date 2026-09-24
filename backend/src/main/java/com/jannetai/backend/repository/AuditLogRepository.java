package com.jannetai.backend.repository;

import com.jannetai.backend.entity.AuditLog;
import com.jannetai.backend.repository.support.AppendOnlyRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Deliberately NOT a JpaRepository (Phase 4 change from the Phase 3
 * skeleton) - audit_logs is append-only (V12__create_audit_logs.sql /
 * Security Section 27.4), so this repository is typed against
 * AppendOnlyRepository, which exposes no delete method at all. Query
 * methods specific to a business module (e.g. an admin audit-trail viewer)
 * are added by the phase that owns that module, not here.
 *
 * Phase 12 addition: a derived finder for
 * {@code service.complaint.ComplaintService#addInternalNote}/
 * {@code #toResponse} - internal notes (SRS 16.2 "Add Internal Note") are
 * stored as append-only AuditLog rows rather than a new table (same
 * precedent as Phase 11's escalation events, see
 * EscalationSchedulerService's Javadoc: "an AuditLog entry is written
 * instead, which is exactly what that table is for"). A derived finder
 * method is permitted on an AppendOnlyRepository the same as on a plain
 * JpaRepository - only the inherited CRUD surface is narrowed, not
 * Spring Data's query-derivation mechanism.
 */
@Repository
public interface AuditLogRepository extends AppendOnlyRepository<AuditLog, Long> {

    List<AuditLog> findByEntityTypeAndEntityIdAndActionTypeOrderByCreatedAtAsc(
            String entityType, Long entityId, String actionType);

    /**
     * Phase 14 (Admin & Settings Module, SRS 15.11 "audit log review" /
     * 16.3 Admin Audit Log screen). Every filter is optional, same
     * optional-parameter JPQL pattern as UserRepository#searchAdminUsers.
     * Read-only by construction (this interface has no save/delete
     * surface for anything other than {@code save}, which AuditService
     * alone calls) - this method cannot be used to alter the trail.
     */
    @Query("""
            SELECT a FROM AuditLog a
            WHERE (:actorId IS NULL OR a.actor.userId = :actorId)
              AND (:entityType IS NULL OR a.entityType = :entityType)
              AND (:actionType IS NULL OR a.actionType = :actionType)
              AND (:from IS NULL OR a.createdAt >= :from)
              AND (:to IS NULL OR a.createdAt <= :to)
            ORDER BY a.createdAt DESC
            """)
    Page<AuditLog> search(@Param("actorId") Long actorId, @Param("entityType") String entityType,
                           @Param("actionType") String actionType, @Param("from") LocalDateTime from,
                           @Param("to") LocalDateTime to, Pageable pageable);

    /**
     * Phase 16 (Admin Dashboard, SRS 24.3 "configuration change audit
     * summary"). {@code entityType IN ('SETTING', 'ROUTING_RULE', 'USER')}
     * covers every admin-configuration action this codebase actually
     * records (PlatformSettingsService's {@code "PLATFORM_SETTING_UPDATED"}
     * / RoutingRuleService's {@code "ROUTING_RULE_CREATED"}/
     * {@code "ROUTING_RULE_DEACTIVATED"} / AdminUserService's
     * {@code "ADMIN_USER_*"} entries) - {@code "COMPLAINT"}-entityType
     * rows (the large majority of this table's volume) are deliberately
     * excluded, since those are operational events, not administrative
     * configuration changes.
     */
    long countByEntityTypeInAndCreatedAtAfter(java.util.Collection<String> entityTypes, LocalDateTime since);
}
