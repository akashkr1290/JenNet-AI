package com.jannetai.backend.service.dashboard;

import com.jannetai.backend.dto.dashboard.AdminDashboardSummaryResponse;
import com.jannetai.backend.dto.dashboard.CategoryTrendPointResponse;
import com.jannetai.backend.dto.dashboard.DepartmentComparisonResponse;
import com.jannetai.backend.dto.dashboard.KpiTilesResponse;
import com.jannetai.backend.dto.dashboard.RoutingRuleEffectivenessResponse;
import com.jannetai.backend.dto.dashboard.WardHeatmapPointResponse;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.RoutingRule;
import com.jannetai.backend.entity.enums.ActorType;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.DeliveryStatus;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.repository.AuditLogRepository;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.NotificationRepository;
import com.jannetai.backend.repository.RoutingRuleRepository;
import com.jannetai.backend.repository.StatusHistoryRepository;
import com.jannetai.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 16 (Government Dashboard Module, SRS 15.10; Analytics Module, SRS
 * 15.14). Pure, stateless aggregation queries - no role-scoping, no
 * caching, no HTTP concerns. {@code GovernmentDashboardService} owns
 * role-scoping/permission checks (mirroring the Phase 13 split between
 * that service and {@code DepartmentPerformanceService}'s own methods -
 * except here the split is between this class, which only computes, and
 * {@link com.jannetai.backend.service.dashboard.AnalyticsCacheService},
 * which decides when to call it and what to serve from cache).
 *
 * DEPARTMENT-SCOPING CONVENTION: every method here that takes a
 * {@code departmentId} treats {@code null} as "jurisdiction-wide, no
 * department filter" - matching SRS 15.10's Admin/Super Admin "sees
 * whole jurisdiction" business rule (see class-level note in
 * {@code ComplaintRepository}'s Phase 16 section for why ADMIN and
 * SUPER_ADMIN are treated identically: this schema models one
 * jurisdiction, not several).
 *
 * TIME-BUCKETING KNOWN LIMITATION (same category as Phase 13's own
 * documented tradeoff): {@link #aggregateHeatmap} and
 * {@link #aggregateCategoryTrend} both load the matching
 * {@link Complaint} rows into memory and group them in Java rather than
 * issuing a SQL {@code GROUP BY} - acceptable at this project's current/
 * expected civic-complaint scale (see
 * {@link ComplaintRepository#findForAnalytics}'s Javadoc), not
 * necessarily at a much larger one. A future phase migrating to a real
 * SQL aggregation (or a dedicated read-model/materialized-view table)
 * should start here.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsAggregationService {

    private static final Set<ComplaintStatus> OPEN_STATUSES = Set.of(
            ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS);
    private static final Set<ComplaintStatus> RESOLVED_STATUSES = Set.of(
            ComplaintStatus.RESOLVED, ComplaintStatus.CLOSED);

    private final ComplaintRepository complaintRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final RoutingRuleRepository routingRuleRepository;
    private final AuditLogRepository auditLogRepository;
    private final NotificationRepository notificationRepository;

    /**
     * SRS 15.10 KPI tiles: open, resolved, average resolution time, SLA
     * compliance - {@code departmentId == null} means jurisdiction-wide
     * (every complaint, using the same jurisdiction-wide repository
     * methods {@code ComplaintRepository} gained this phase), otherwise
     * identical formulas to Phase 13's {@code DepartmentPerformanceService.getPerformance}.
     */
    @Transactional(readOnly = true)
    public KpiTilesResponse computeKpiTiles(Long departmentId) {
        long total;
        long open;
        long resolved;
        long escalated;
        List<Complaint> resolvedComplaints;

        if (departmentId == null) {
            total = complaintRepository.count();
            open = complaintRepository.countByStatusIn(OPEN_STATUSES);
            resolved = complaintRepository.countByStatusIn(RESOLVED_STATUSES);
            escalated = complaintRepository.countByIsEscalated(true);
            resolvedComplaints = complaintRepository.findByStatusIn(RESOLVED_STATUSES);
        } else {
            total = complaintRepository.countByDepartment_DepartmentId(departmentId);
            open = complaintRepository.countByDepartment_DepartmentIdAndStatusIn(departmentId, OPEN_STATUSES);
            resolved = complaintRepository.countByDepartment_DepartmentIdAndStatusIn(departmentId, RESOLVED_STATUSES);
            escalated = complaintRepository.countByDepartment_DepartmentIdAndIsEscalated(departmentId, true);
            resolvedComplaints = complaintRepository.findByDepartment_DepartmentIdAndStatusIn(
                    departmentId, RESOLVED_STATUSES);
        }

        Double slaCompliancePercent = total == 0 ? 100.0 : (double) (total - escalated) / total * 100.0;
        Double avgResolutionHours = averageResolutionHours(resolvedComplaints);

        return new KpiTilesResponse(total, open, resolved, escalated,
                round1(slaCompliancePercent), round1(avgResolutionHours));
    }

    /**
     * SRS 15.10/16.3/24.4 ward-density heatmap. See
     * {@link ComplaintRepository#findForAnalytics}'s Javadoc for the
     * candidate-pool query and this class's own Javadoc for the in-Java
     * grouping tradeoff. A ward with zero complaints in the window is
     * absent from the result, not returned with a zero count - matching
     * {@link WardHeatmapPointResponse}'s own documented convention.
     * Complaints with no resolved location/ward (e.g. still mid-GPS-
     * resolution, or explicitly out-of-jurisdiction) are excluded, since
     * they have nothing to bucket into.
     */
    @Transactional(readOnly = true)
    public List<WardHeatmapPointResponse> aggregateHeatmap(Long departmentId, LocalDateTime since, LocalDateTime until) {
        List<Complaint> candidates = complaintRepository.findForAnalytics(departmentId, since, until);

        Map<Long, String> wardNames = new LinkedHashMap<>();
        Map<Long, Long> totalCounts = new LinkedHashMap<>();
        Map<Long, Long> openCounts = new LinkedHashMap<>();

        for (Complaint c : candidates) {
            if (c.getLocation() == null || c.getLocation().getWard() == null) {
                continue;
            }
            Long wardId = c.getLocation().getWard().getWardId();
            wardNames.putIfAbsent(wardId, c.getLocation().getWard().getName());
            totalCounts.merge(wardId, 1L, Long::sum);
            if (OPEN_STATUSES.contains(c.getStatus())) {
                openCounts.merge(wardId, 1L, Long::sum);
            }
        }

        List<WardHeatmapPointResponse> result = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : totalCounts.entrySet()) {
            Long wardId = entry.getKey();
            result.add(new WardHeatmapPointResponse(
                    wardId, wardNames.get(wardId), entry.getValue(), openCounts.getOrDefault(wardId, 0L)));
        }
        result.sort(Comparator.comparing(WardHeatmapPointResponse::complaintCount).reversed());
        return result;
    }

    /**
     * SRS 15.10/24.4 category trend chart. Bucketed by calendar day (the
     * server JVM's default zone, consistent with every other
     * {@code LocalDateTime} field in this codebase, which is never
     * stored with an explicit zone - see {@code Complaint.createdAt}'s
     * own un-zoned type) - a caller wanting weekly/monthly buckets
     * aggregates these daily points client-side, avoiding the need for
     * this endpoint to support multiple bucket granularities server-side
     * for what SRS 15.10 only ever calls "trend charts... by time
     * period" without specifying a granularity.
     */
    @Transactional(readOnly = true)
    public List<CategoryTrendPointResponse> aggregateCategoryTrend(
            Long departmentId, LocalDateTime since, LocalDateTime until) {
        List<Complaint> candidates = complaintRepository.findForAnalytics(departmentId, since, until);

        Map<String, CategoryTrendPointResponse> buckets = new LinkedHashMap<>();
        for (Complaint c : candidates) {
            if (c.getCreatedAt() == null) {
                continue;
            }
            var bucketDate = c.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate();
            String key = c.getCategory() + "|" + bucketDate;
            buckets.merge(key, new CategoryTrendPointResponse(c.getCategory(), bucketDate, 1L),
                    (existing, addition) -> new CategoryTrendPointResponse(
                            existing.category(), existing.bucketDate(), existing.count() + 1));
        }

        List<CategoryTrendPointResponse> result = new ArrayList<>(buckets.values());
        result.sort(Comparator.comparing(CategoryTrendPointResponse::bucketDate)
                .thenComparing(p -> p.category().name()));
        return result;
    }

    /**
     * SRS 15.10 UI Components / SRS 24.3 Charts: "department comparison".
     * Jurisdiction-wide only (ADMIN/SUPER_ADMIN) - one row per active
     * department, reusing the exact same per-department KPI formulas as
     * {@link #computeKpiTiles} rather than a separate implementation.
     */
    @Transactional(readOnly = true)
    public List<DepartmentComparisonResponse> computeDepartmentComparison() {
        List<Department> departments = departmentRepository.findByIsActiveTrueOrderByNameAsc();
        List<DepartmentComparisonResponse> result = new ArrayList<>();
        for (Department department : departments) {
            KpiTilesResponse kpis = computeKpiTiles(department.getDepartmentId());
            result.add(new DepartmentComparisonResponse(
                    department.getDepartmentId(),
                    department.getName(),
                    kpis.totalComplaints(),
                    kpis.openComplaints(),
                    kpis.resolvedComplaints(),
                    kpis.slaCompliancePercent()));
        }
        return result;
    }

    /**
     * SRS 24.3 Admin Dashboard - every platform-wide statistic that
     * screen names. See {@link AdminDashboardSummaryResponse}'s Javadoc
     * for the exact scope/formula decisions (AI-vs-manual rate,
     * duplicate-merge rate, routing-rule effectiveness, configuration-
     * change audit summary, and the "system health" substitution for the
     * SRS's separate infrastructure-monitoring section).
     */
    @Transactional(readOnly = true)
    public AdminDashboardSummaryResponse computeAdminSummary() {
        long activeUsers = userRepository.countByStatus(UserStatus.ACTIVE);
        long totalUsers = userRepository.count();

        long totalComplaints = complaintRepository.count();
        long openComplaints = complaintRepository.countByStatusIn(OPEN_STATUSES);
        long resolvedComplaints = complaintRepository.countByStatusIn(RESOLVED_STATUSES);

        long aiAutoVerified = statusHistoryRepository.countByNewStatusAndActorType(
                ComplaintStatus.VERIFIED, ActorType.SYSTEM);
        long aiAutoDuplicateMerged = statusHistoryRepository.countByNewStatusAndActorType(
                ComplaintStatus.DUPLICATE, ActorType.SYSTEM);
        long aiAutoProcessed = aiAutoVerified + aiAutoDuplicateMerged;

        long manualVerified = statusHistoryRepository.countByNewStatusAndActorTypeNot(
                ComplaintStatus.VERIFIED, ActorType.SYSTEM);
        long manualDuplicate = statusHistoryRepository.countByNewStatusAndActorTypeNot(
                ComplaintStatus.DUPLICATE, ActorType.SYSTEM);
        long manualProcessed = manualVerified + manualDuplicate;

        long totalProcessed = aiAutoProcessed + manualProcessed;
        Double aiRatePercent = totalProcessed == 0 ? null : (double) aiAutoProcessed / totalProcessed * 100.0;

        // Duplicate-merge rate: DRAFT rows are excluded from both sides -
        // see ComplaintRepository#countByStatusNot's Javadoc.
        long nonDraftTotal = complaintRepository.countByStatusNot(ComplaintStatus.DRAFT);
        long duplicateCount = complaintRepository.countByStatus(ComplaintStatus.DUPLICATE);
        Double duplicateRatePercent = nonDraftTotal == 0 ? 0.0 : (double) duplicateCount / nonDraftTotal * 100.0;

        List<RoutingRuleEffectivenessResponse> routingRuleEffectiveness = computeRoutingRuleEffectiveness();

        LocalDateTime last30Days = LocalDateTime.now().minusDays(30);
        long configChanges = auditLogRepository.countByEntityTypeInAndCreatedAtAfter(
                Set.of("SETTING", "ROUTING_RULE", "USER"), last30Days);

        long notificationsLast30Days = notificationRepository.countByCreatedAtAfter(last30Days);
        long notificationFailuresLast30Days = notificationRepository.countByDeliveryStatusAndCreatedAtAfter(
                DeliveryStatus.FAILED, last30Days);
        Double notificationFailureRate = notificationsLast30Days == 0 ? 0.0
                : (double) notificationFailuresLast30Days / notificationsLast30Days * 100.0;

        long currentlyEscalated = complaintRepository.countByIsEscalated(true);

        return new AdminDashboardSummaryResponse(
                activeUsers,
                totalUsers,
                totalComplaints,
                openComplaints,
                resolvedComplaints,
                aiAutoProcessed,
                manualProcessed,
                round1(aiRatePercent),
                duplicateCount,
                round1(duplicateRatePercent),
                routingRuleEffectiveness,
                configChanges,
                notificationFailuresLast30Days,
                round1(notificationFailureRate),
                currentlyEscalated,
                LocalDateTime.now());
    }

    /** See {@link RoutingRuleEffectivenessResponse}'s Javadoc for the exact formula this implements. */
    private List<RoutingRuleEffectivenessResponse> computeRoutingRuleEffectiveness() {
        List<RoutingRule> activeRules = routingRuleRepository.findByIsActiveTrueOrderByIssueCategoryAsc();
        List<RoutingRuleEffectivenessResponse> result = new ArrayList<>();
        for (RoutingRule rule : activeRules) {
            Long departmentId = rule.getDepartment().getDepartmentId();
            long count = complaintRepository.countByDepartment_DepartmentIdAndCategory(
                    departmentId, rule.getIssueCategory());
            long escalated = complaintRepository.countByDepartment_DepartmentIdAndCategoryAndIsEscalated(
                    departmentId, rule.getIssueCategory(), true);
            Double slaCompliance = count == 0 ? 100.0 : (double) (count - escalated) / count * 100.0;
            result.add(new RoutingRuleEffectivenessResponse(
                    rule.getRoutingRuleId(), rule.getIssueCategory(), rule.getDepartment().getName(),
                    count, round1(slaCompliance)));
        }
        return result;
    }

    /** Same "null, not zero, when nothing has completed yet" convention as Phase 13's identical helper. */
    private Double averageResolutionHours(List<Complaint> resolvedOrClosed) {
        if (resolvedOrClosed.isEmpty()) {
            return null;
        }
        double totalHours = 0;
        for (Complaint c : resolvedOrClosed) {
            if (c.getCreatedAt() != null && c.getUpdatedAt() != null) {
                totalHours += Duration.between(c.getCreatedAt(), c.getUpdatedAt()).toMinutes() / 60.0;
            }
        }
        return totalHours / resolvedOrClosed.size();
    }

    private static Double round1(Double value) {
        return value == null ? null : Math.round(value * 10.0) / 10.0;
    }
}
