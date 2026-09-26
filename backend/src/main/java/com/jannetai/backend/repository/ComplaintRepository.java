package com.jannetai.backend.repository;

import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Severity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Phase 6 (Complaint Module) adds the query methods the module actually
 * needs: citizen-scoped listing/tracking (SRS 15.1), staff-scoped listing
 * with the same status/category filters (SRS Table 23's
 * GET /api/v1/complaints query params), and the anti-spam rolling-24h
 * count (SRS 15.1 Business Rules). Kept as derived/JPQL query methods, not
 * a Specification API, consistent with this project's stated preference
 * for the simplest thing that satisfies the SRS (ARCHITECTURE.md Section
 * 7) - the filter set here is small and fixed.
 */
@Repository
public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

    boolean existsByReferenceNumber(String referenceNumber);

    /** SRS 15.1 anti-spam control: "max 10 complaints per rolling 24-hour period". */
    long countByCitizen_UserIdAndCreatedAtAfter(Long citizenId, LocalDateTime since);

    @Query("""
            SELECT c FROM Complaint c
            WHERE c.citizen.userId = :citizenId
              AND (:status IS NULL OR c.status = :status)
              AND (:category IS NULL OR c.category = :category)
            ORDER BY c.createdAt DESC
            """)
    Page<Complaint> findForCitizen(@Param("citizenId") Long citizenId,
                                    @Param("status") ComplaintStatus status,
                                    @Param("category") ComplaintCategory category,
                                    Pageable pageable);

    /**
     * Staff-tier listing (VERIFICATION_TEAM/ADMIN/SUPER_ADMIN - the roles
     * for which Phase 12 does not enforce a default department/officer
     * restriction; see {@link #findForOfficerOrDepartment} for
     * GOVERNMENT_OFFICER/DEPARTMENT_HEAD). {@code departmentId} remains an
     * optional self-filter these unrestricted roles may still supply.
     * PARTIALLY SUPERSEDED Phase 12: the "every non-citizen role sees
     * every complaint" scope-widening this Javadoc originally documented
     * (Phase 6) now only applies to this narrower role set - see
     * PROJECT_INTEGRATION.md Section 6 for the full history.
     */
    @Query("""
            SELECT c FROM Complaint c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:category IS NULL OR c.category = :category)
              AND (:departmentId IS NULL OR c.department.departmentId = :departmentId)
            ORDER BY c.createdAt DESC
            """)
    Page<Complaint> findForStaff(@Param("status") ComplaintStatus status,
                                  @Param("category") ComplaintCategory category,
                                  @Param("departmentId") Long departmentId,
                                  Pageable pageable);

    /**
     * Phase 12 (Officer Module, SRS 16.2 Officer Queue Permissions:
     * "Government Officer (own queue), Department Head (whole department
     * queue)"). Server-side default scoping (ARCHITECTURE.md Section 5's
     * "data visibility is scoped by role... not merely hidden client-
     * side" principle, applied here for the first time to the officer-
     * facing complaint queue rather than only the Phase 16 dashboard).
     * {@code officerId} is non-null only for GOVERNMENT_OFFICER callers
     * (their own {@code user_id}, restricting to complaints assigned
     * directly to them); {@code departmentId} is always non-null for both
     * roles (the officer's own department, or the department head's own
     * department) since an officer with no department has nothing to
     * queue. See ComplaintService.list for how the two roles choose which
     * arguments to pass.
     */
    @Query("""
            SELECT c FROM Complaint c
            WHERE c.department.departmentId = :departmentId
              AND (:officerId IS NULL OR c.assignedOfficer.userId = :officerId)
              AND (:status IS NULL OR c.status = :status)
              AND (:category IS NULL OR c.category = :category)
            ORDER BY c.createdAt DESC
            """)
    Page<Complaint> findForOfficerOrDepartment(@Param("departmentId") Long departmentId,
                                                @Param("officerId") Long officerId,
                                                @Param("status") ComplaintStatus status,
                                                @Param("category") ComplaintCategory category,
                                                Pageable pageable);

    // ---- Audit GAP-040: staff queue ordering by severity / persisted SLA deadline (see dto.complaint.ComplaintSort).
    // Same filters as findForStaff / findForOfficerOrDepartment; the ORDER BY is fixed in the query, so callers pass an
    // unsorted Pageable. Severity is stored as a STRING enum, hence the explicit rank instead of ORDER BY c.severity.

    @Query(value = """
            SELECT c FROM Complaint c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:category IS NULL OR c.category = :category)
              AND (:departmentId IS NULL OR c.department.departmentId = :departmentId)
            ORDER BY CASE c.severity WHEN com.jannetai.backend.entity.enums.Severity.CRITICAL THEN 0 WHEN com.jannetai.backend.entity.enums.Severity.HIGH THEN 1 WHEN com.jannetai.backend.entity.enums.Severity.MEDIUM THEN 2 WHEN com.jannetai.backend.entity.enums.Severity.LOW THEN 3 ELSE 4 END ASC,
                     CASE WHEN c.slaDueAt IS NULL THEN 1 ELSE 0 END ASC, c.slaDueAt ASC, c.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(c) FROM Complaint c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:category IS NULL OR c.category = :category)
              AND (:departmentId IS NULL OR c.department.departmentId = :departmentId)
            """)
    Page<Complaint> findForStaffBySeverity(@Param("status") ComplaintStatus status,
                                            @Param("category") ComplaintCategory category,
                                            @Param("departmentId") Long departmentId,
                                            Pageable pageable);

    @Query(value = """
            SELECT c FROM Complaint c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:category IS NULL OR c.category = :category)
              AND (:departmentId IS NULL OR c.department.departmentId = :departmentId)
            ORDER BY CASE WHEN c.slaDueAt IS NULL THEN 1 ELSE 0 END ASC, c.slaDueAt ASC,
                     CASE c.severity WHEN com.jannetai.backend.entity.enums.Severity.CRITICAL THEN 0 WHEN com.jannetai.backend.entity.enums.Severity.HIGH THEN 1 WHEN com.jannetai.backend.entity.enums.Severity.MEDIUM THEN 2 WHEN com.jannetai.backend.entity.enums.Severity.LOW THEN 3 ELSE 4 END ASC, c.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(c) FROM Complaint c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:category IS NULL OR c.category = :category)
              AND (:departmentId IS NULL OR c.department.departmentId = :departmentId)
            """)
    Page<Complaint> findForStaffBySlaDue(@Param("status") ComplaintStatus status,
                                            @Param("category") ComplaintCategory category,
                                            @Param("departmentId") Long departmentId,
                                            Pageable pageable);

    @Query(value = """
            SELECT c FROM Complaint c
            WHERE c.department.departmentId = :departmentId
              AND (:officerId IS NULL OR c.assignedOfficer.userId = :officerId)
              AND (:status IS NULL OR c.status = :status)
              AND (:category IS NULL OR c.category = :category)
            ORDER BY CASE c.severity WHEN com.jannetai.backend.entity.enums.Severity.CRITICAL THEN 0 WHEN com.jannetai.backend.entity.enums.Severity.HIGH THEN 1 WHEN com.jannetai.backend.entity.enums.Severity.MEDIUM THEN 2 WHEN com.jannetai.backend.entity.enums.Severity.LOW THEN 3 ELSE 4 END ASC,
                     CASE WHEN c.slaDueAt IS NULL THEN 1 ELSE 0 END ASC, c.slaDueAt ASC, c.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(c) FROM Complaint c
            WHERE c.department.departmentId = :departmentId
              AND (:officerId IS NULL OR c.assignedOfficer.userId = :officerId)
              AND (:status IS NULL OR c.status = :status)
              AND (:category IS NULL OR c.category = :category)
            """)
    Page<Complaint> findForOfficerOrDepartmentBySeverity(@Param("departmentId") Long departmentId,
                                            @Param("officerId") Long officerId,
                                            @Param("status") ComplaintStatus status,
                                            @Param("category") ComplaintCategory category,
                                            Pageable pageable);

    @Query(value = """
            SELECT c FROM Complaint c
            WHERE c.department.departmentId = :departmentId
              AND (:officerId IS NULL OR c.assignedOfficer.userId = :officerId)
              AND (:status IS NULL OR c.status = :status)
              AND (:category IS NULL OR c.category = :category)
            ORDER BY CASE WHEN c.slaDueAt IS NULL THEN 1 ELSE 0 END ASC, c.slaDueAt ASC,
                     CASE c.severity WHEN com.jannetai.backend.entity.enums.Severity.CRITICAL THEN 0 WHEN com.jannetai.backend.entity.enums.Severity.HIGH THEN 1 WHEN com.jannetai.backend.entity.enums.Severity.MEDIUM THEN 2 WHEN com.jannetai.backend.entity.enums.Severity.LOW THEN 3 ELSE 4 END ASC, c.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(c) FROM Complaint c
            WHERE c.department.departmentId = :departmentId
              AND (:officerId IS NULL OR c.assignedOfficer.userId = :officerId)
              AND (:status IS NULL OR c.status = :status)
              AND (:category IS NULL OR c.category = :category)
            """)
    Page<Complaint> findForOfficerOrDepartmentBySlaDue(@Param("departmentId") Long departmentId,
                                            @Param("officerId") Long officerId,
                                            @Param("status") ComplaintStatus status,
                                            @Param("category") ComplaintCategory category,
                                            Pageable pageable);

    /**
     * Audit GAP-031 (SRS 15.5: coordinates outside the configured boundary are
     * "flagged 'out of jurisdiction' for Admin review"): the Admin review queue.
     */
    @Query("""
            SELECT c FROM Complaint c
            WHERE c.location.outOfJurisdiction = true
              AND c.status NOT IN :excludedStatuses
            ORDER BY c.createdAt DESC
            """)
    Page<Complaint> findOutOfJurisdiction(@Param("excludedStatuses") Collection<ComplaintStatus> excludedStatuses,
                                          Pageable pageable);

    /**
     * Phase 9 (Duplicate Detection Module, SRS 15.6 Inputs: "existing open
     * complaints in the same ward"). Candidate pool for
     * {@code service.complaint.DuplicateDetectionService} to send to
     * ai-service's {@code /duplicate-check} for image+geo comparison -
     * this query only does the cheap, structural pre-filtering (ward,
     * "open" status, time window, not-self); the actual similarity/
     * precise-proximity decision is ai-service's job.
     * {@code pageable} caps the candidate count (see
     * {@code app.duplicate-detection.max-candidates}) so a busy ward
     * doesn't balloon a single request into dozens of image comparisons.
     */
    @Query("""
            SELECT c FROM Complaint c
            WHERE c.location.ward.wardId = :wardId
              AND c.complaintId <> :excludeComplaintId
              AND c.status IN :statuses
              AND c.createdAt >= :since
            ORDER BY c.createdAt DESC
            """)
    List<Complaint> findDuplicateCandidates(@Param("wardId") Long wardId,
                                             @Param("excludeComplaintId") Long excludeComplaintId,
                                             @Param("statuses") Collection<ComplaintStatus> statuses,
                                             @Param("since") LocalDateTime since,
                                             Pageable pageable);

    /**
     * Audit GAP-008 (SRS 15.6 / 21.5: "within 50 m and 30 days"): candidates by
     * position instead of ward - GPS complaints often have no ward, so the
     * ward-only query above never found them. A bounding box around the point
     * (the caller adds a margin over 50 m) is the cheap pre-filter; ai-service
     * applies the exact distance rule. Approximate WARD_FALLBACK points are
     * excluded - they are not real positions.
     */
    @Query("""
            SELECT c FROM Complaint c
            WHERE c.location.latitude BETWEEN :minLat AND :maxLat
              AND c.location.longitude BETWEEN :minLng AND :maxLng
              AND c.location.source <> com.jannetai.backend.entity.enums.LocationSource.WARD_FALLBACK
              AND c.complaintId <> :excludeComplaintId
              AND c.status IN :statuses
              AND c.createdAt >= :since
            ORDER BY c.createdAt DESC
            """)
    List<Complaint> findDuplicateCandidatesNear(@Param("minLat") java.math.BigDecimal minLat,
                                                 @Param("maxLat") java.math.BigDecimal maxLat,
                                                 @Param("minLng") java.math.BigDecimal minLng,
                                                 @Param("maxLng") java.math.BigDecimal maxLng,
                                                 @Param("excludeComplaintId") Long excludeComplaintId,
                                                 @Param("statuses") Collection<ComplaintStatus> statuses,
                                                 @Param("since") LocalDateTime since,
                                                 Pageable pageable);

    /**
     * Phase 11 (Department Assignment Module, SRS 15.7 Business Rules:
     * "officer assignment considers current open-complaint load"). "Open"
     * here means ASSIGNED or IN_PROGRESS - a complaint still actively on
     * an officer's plate; RESOLVED/CLOSED/REJECTED/DUPLICATE don't count
     * against them. See PROJECT_INTEGRATION.md Section 6 for why this
     * doesn't also filter by ward.
     */
    long countByAssignedOfficer_UserIdAndStatusIn(Long officerId, Collection<ComplaintStatus> statuses);

    /**
     * Audit GAP-028 (SRS 14.1 step 27): RESOLVED complaints whose grace period
     * has run out - measured from the latest transition INTO Resolved (status
     * history), or updated_at for legacy rows without one.
     */
    @Query("""
            SELECT c FROM Complaint c
            WHERE c.status = com.jannetai.backend.entity.enums.ComplaintStatus.RESOLVED
              AND (
                (SELECT MAX(h.changedAt) FROM StatusHistory h
                  WHERE h.complaint = c
                    AND h.newStatus = com.jannetai.backend.entity.enums.ComplaintStatus.RESOLVED) < :cutoff
                OR (NOT EXISTS (SELECT h2.historyId FROM StatusHistory h2
                                 WHERE h2.complaint = c
                                   AND h2.newStatus = com.jannetai.backend.entity.enums.ComplaintStatus.RESOLVED)
                    AND c.updatedAt < :cutoff)
              )
            ORDER BY c.complaintId ASC
            """)
    List<Complaint> findResolvedPastGracePeriod(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    // ---- Audit GAP-027: persisted SLA clock (V27) ----

    /** Breached, not yet escalated: sla_due_at has passed while still Assigned/In Progress. */
    @Query("""
            SELECT c FROM Complaint c
            WHERE c.isEscalated = false
              AND c.status IN :statuses
              AND c.slaDueAt IS NOT NULL
              AND c.slaDueAt <= :now
            """)
    List<Complaint> findSlaDueBreaches(@Param("statuses") Collection<ComplaintStatus> statuses,
                                       @Param("now") LocalDateTime now);

    /** In the 80 % warning period (warning point passed, due time not yet), with an officer to warn. */
    @Query("""
            SELECT c FROM Complaint c
            WHERE c.isEscalated = false
              AND c.status IN :statuses
              AND c.assignedOfficer IS NOT NULL
              AND c.slaWarningAt IS NOT NULL
              AND c.slaWarningAt <= :now
              AND c.slaDueAt > :now
            """)
    List<Complaint> findSlaWarningsDue(@Param("statuses") Collection<ComplaintStatus> statuses,
                                       @Param("now") LocalDateTime now);

    /** Open complaints from before V27 (or whose severity arrived later) that have no clock yet. */
    @Query("""
            SELECT c FROM Complaint c
            WHERE c.status IN :statuses
              AND c.slaDueAt IS NULL
              AND c.severity IS NOT NULL
            """)
    List<Complaint> findClockedStatusWithoutSlaClock(@Param("statuses") Collection<ComplaintStatus> statuses);

    // ---- Phase 13 (Department Head Module, SRS 16.2 "Department Performance
    // View": KPI tiles, officer workload table, SLA compliance chart) ----

    /** Department KPI tile: total complaints ever routed to this department. */
    long countByDepartment_DepartmentId(Long departmentId);

    /** Department KPI tile: complaints in one of the given statuses (used for the "open" count). */
    long countByDepartment_DepartmentIdAndStatusIn(Long departmentId, Collection<ComplaintStatus> statuses);

    /**
     * SLA-compliance tile numerator/denominator support. There is no
     * dedicated per-complaint "SLA compliant/breached" column - this
     * reuses the same {@code is_escalated} flag {@link EscalationSchedulerService}
     * (via {@code com.jannetai.backend.service.department}) already sets on an
     * SLA breach (Phase 11), the same simplification precedent as that
     * class's own "ESCALATED is an annotation, not a new table" choice.
     * See PROJECT_INTEGRATION.md Section 6 for the exact SLA-compliance-%
     * formula this backs.
     */
    long countByDepartment_DepartmentIdAndIsEscalated(Long departmentId, Boolean isEscalated);

    /**
     * Average-resolution-time tile support: every RESOLVED/CLOSED complaint
     * ever routed to this department, so {@code DepartmentPerformanceService}
     * can compute {@code updatedAt - createdAt} per row in Java. Deliberately
     * not a SQL AVG(TIMESTAMPDIFF(...)) native query - see that class's
     * Javadoc "AVERAGE RESOLUTION TIME" note for why the simpler in-Java
     * approach was chosen (consistent with this project's stated preference
     * for the simplest thing that satisfies the SRS, ARCHITECTURE.md Section 7).
     */
    List<Complaint> findByDepartment_DepartmentIdAndStatusIn(Long departmentId, Collection<ComplaintStatus> statuses);

    /** Per-officer SLA-breach count for the officer workload table (same flag/semantics as the department-level tile above). */
    long countByAssignedOfficer_UserIdAndIsEscalated(Long officerId, Boolean isEscalated);

    /** Per-officer average-resolution-time support - same approach as {@link #findByDepartment_DepartmentIdAndStatusIn}, scoped to one officer. */
    List<Complaint> findByAssignedOfficer_UserIdAndStatusIn(Long officerId, Collection<ComplaintStatus> statuses);

    // ---- Phase 16 (Government Dashboard Module, SRS 15.10/15.14) ----

    /**
     * Platform-wide (jurisdiction) equivalents of the Phase 13 per-
     * department KPI-tile methods above - used when
     * {@code AnalyticsAggregationService} computes the jurisdiction-wide
     * snapshot ({@code departmentId == null}), i.e. what an ADMIN/
     * SUPER_ADMIN sees with no department filter (SRS 15.10 Business
     * Rules: "Admin sees whole jurisdiction; Super Admin sees all
     * jurisdictions" - this codebase models a single jurisdiction, see
     * PROJECT_INTEGRATION.md Section 6 for why ADMIN/SUPER_ADMIN are
     * treated identically here).
     */
    long countByStatusIn(Collection<ComplaintStatus> statuses);

    /** Jurisdiction-wide equivalent of {@link #countByDepartment_DepartmentIdAndIsEscalated}. */
    long countByIsEscalated(Boolean isEscalated);

    /** Jurisdiction-wide equivalent of {@link #findByDepartment_DepartmentIdAndStatusIn}. */
    List<Complaint> findByStatusIn(Collection<ComplaintStatus> statuses);

    /**
     * Admin Dashboard "duplicate-merge rate" (SRS 24.3) numerator/
     * denominator support: how many complaints ever reached the
     * {@code DUPLICATE} status (system auto-merged, Phase 9, or manually
     * decided, Phase 6), out of every complaint the platform has ever
     * accepted past DRAFT. {@code count()} (inherited) is the
     * denominator's simplest available superset - DRAFT rows are
     * transient/abandoned submissions never sent to the AI pipeline at
     * all (ComplaintService#create's own "DRAFT is not yet a submitted
     * complaint" semantics) - see
     * {@code AnalyticsAggregationService#computeAdminSummary}'s Javadoc
     * for why DRAFT is excluded from both sides of this ratio rather
     * than only the numerator.
     */
    long countByStatus(ComplaintStatus status);

    long countByStatusNot(ComplaintStatus status);

    /**
     * Routing-rule-effectiveness support (SRS 24.3): total and SLA-
     * breached complaint counts for one exact (category, department)
     * pairing - the same pairing a {@code RoutingRule} row represents.
     * Reuses the Phase 13 {@code is_escalated}-as-SLA-breach-flag
     * convention (see {@link #countByDepartment_DepartmentIdAndIsEscalated}'s
     * Javadoc) rather than inventing a new per-rule tracking column.
     */
    long countByDepartment_DepartmentIdAndCategory(Long departmentId, ComplaintCategory category);

    long countByDepartment_DepartmentIdAndCategoryAndIsEscalated(
            Long departmentId, ComplaintCategory category, Boolean isEscalated);

    /**
     * Shared candidate pool for both the ward-density heatmap (SRS 15.10/
     * 16.3/24.4) and the category trend chart (SRS 15.10/24.4) - both
     * are computed in Java from the same date-ranged, optionally
     * department-scoped result set rather than two separate SQL
     * {@code GROUP BY} queries, consistent with this project's existing
     * "simplest thing that satisfies the SRS" precedent
     * ({@code DepartmentPerformanceService}'s average-resolution-time
     * Javadoc makes the identical tradeoff explicitly). {@code since}/
     * {@code until} bound {@code createdAt} (a complaint's submission
     * time, not its current status) since both charts are about
     * complaint-volume-over-time, not current-status snapshots. See
     * {@code AnalyticsAggregationService}'s class Javadoc for the same
     * KNOWN LIMITATION Phase 13 already documented: acceptable at this
     * project's current/expected civic-complaint scale, not necessarily
     * a much larger one.
     */
    @Query("""
            SELECT c FROM Complaint c
            WHERE (:departmentId IS NULL OR c.department.departmentId = :departmentId)
              AND c.createdAt >= :since
              AND c.createdAt <= :until
            """)
    List<Complaint> findForAnalytics(@Param("departmentId") Long departmentId,
                                      @Param("since") LocalDateTime since,
                                      @Param("until") LocalDateTime until);

    /** Audit GAP-039: complaints received in [start, endExclusive), optionally one department (period reports). */
    @Query("""
            SELECT c FROM Complaint c
            LEFT JOIN FETCH c.location l
            LEFT JOIN FETCH l.ward
            WHERE (:departmentId IS NULL OR c.department.departmentId = :departmentId)
              AND c.createdAt >= :start
              AND c.createdAt < :endExclusive
            """)
    List<Complaint> findReceivedInPeriod(@Param("departmentId") Long departmentId,
                                         @Param("start") LocalDateTime start,
                                         @Param("endExclusive") LocalDateTime endExclusive);

    // ---- Gap-backlog Patch 08/09 (Sep 2026 strict recheck): personal dashboards ----

    @Query("SELECT c.status, COUNT(c) FROM Complaint c WHERE c.citizen.userId = :citizenId GROUP BY c.status")
    List<Object[]> countByStatusForCitizen(@Param("citizenId") Long citizenId);

    List<Complaint> findTop5ByCitizen_UserIdOrderByCreatedAtDesc(Long citizenId);

    @Query("SELECT c.status, COUNT(c) FROM Complaint c WHERE c.assignedOfficer.userId = :officerId GROUP BY c.status")
    List<Object[]> countByStatusForOfficer(@Param("officerId") Long officerId);

    /** Audit GAP-041: a citizen's own complaints for a personal-data export (SRS 24 access request). */
    List<Complaint> findByCitizen_UserIdOrderByCreatedAtDesc(Long citizenId);
}
