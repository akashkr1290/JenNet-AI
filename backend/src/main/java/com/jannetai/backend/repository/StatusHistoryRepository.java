package com.jannetai.backend.repository;

import com.jannetai.backend.entity.StatusHistory;
import com.jannetai.backend.entity.enums.ActorType;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.repository.support.AppendOnlyRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Phase 6 change from the Phase 3 skeleton: re-typed from JpaRepository to
 * {@link AppendOnlyRepository}, mirroring the Phase 4 change already made
 * to AuditLogRepository. V10__create_status_history.sql's own header
 * calls this table an "append-only audit trail" and SRS 15.3 requires
 * "every status change must be recorded" - nothing in this module should
 * ever be able to delete a transition record, so the interface itself
 * should not expose a delete method, same reasoning as AuditLogRepository
 * (see that class's Javadoc).
 */
@Repository
public interface StatusHistoryRepository extends AppendOnlyRepository<StatusHistory, Long> {

    List<StatusHistory> findByComplaint_ComplaintIdOrderByChangedAtAsc(Long complaintId);

    /** Audit GAP-028: the latest transition INTO a status (e.g. when it was resolved). */
    java.util.Optional<StatusHistory> findFirstByComplaint_ComplaintIdAndNewStatusOrderByChangedAtDesc(
            Long complaintId, com.jannetai.backend.entity.enums.ComplaintStatus newStatus);

    /**
     * Phase 16 (Admin Dashboard, SRS 24.3 "AI auto-processing rate versus
     * manual verification rate"). {@code ActorType.SYSTEM} is written
     * only by {@code AiClassificationService}'s two automated paths
     * (auto-verify, Phase 8; auto-merge-as-duplicate, Phase 9) - every
     * other actor type on a {@code VERIFIED}/{@code DUPLICATE} transition
     * represents a human decision (staff verification override, Phase 6;
     * a Department Head/Officer action, etc.). Counting distinct
     * {@link StatusHistory} rows here (not distinct complaints) is
     * deliberate and matches how every other rate/count in this dashboard
     * module is computed - a complaint can only reach {@code VERIFIED} or
     * {@code DUPLICATE} once in its lifecycle (ComplaintStateMachine's
     * transition table has no path back into either from a later state),
     * so row-count and complaint-count are equivalent here in practice,
     * but counting rows is the simpler, more literal reading of "how many
     * times did the system do this" that this SRS line asks for.
     */
    long countByNewStatusAndActorType(ComplaintStatus newStatus, ActorType actorType);

    long countByNewStatusAndActorTypeNot(ComplaintStatus newStatus, ActorType actorType);

    /** Gap-backlog Patch 09: an officer's own RESOLVED transitions since a cut-off, for personal performance. */
    List<StatusHistory> findByActor_UserIdAndNewStatusAndChangedAtGreaterThanEqual(
            Long actorUserId, ComplaintStatus newStatus, LocalDateTime since);

    /**
     * Audit GAP-039: transitions to the given statuses in [start, endExclusive),
     * optionally for one department (the complaint's department now), for period reports.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT h FROM StatusHistory h
            JOIN FETCH h.complaint c
            WHERE h.newStatus IN :statuses
              AND h.changedAt >= :start
              AND h.changedAt < :endExclusive
              AND (:departmentId IS NULL OR c.department.departmentId = :departmentId)
            """)
    List<StatusHistory> findTransitionsInPeriod(
            @org.springframework.data.repository.query.Param("statuses") java.util.Collection<com.jannetai.backend.entity.enums.ComplaintStatus> statuses,
            @org.springframework.data.repository.query.Param("start") java.time.LocalDateTime start,
            @org.springframework.data.repository.query.Param("endExclusive") java.time.LocalDateTime endExclusive,
            @org.springframework.data.repository.query.Param("departmentId") Long departmentId);
}
