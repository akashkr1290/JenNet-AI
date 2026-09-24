package com.jannetai.backend.service.dashboard;

import com.jannetai.backend.dto.dashboard.AdminDashboardSummaryResponse;
import com.jannetai.backend.dto.dashboard.CategoryForecastResponse;
import com.jannetai.backend.dto.dashboard.CategoryTrendPointResponse;
import com.jannetai.backend.dto.dashboard.DepartmentComparisonResponse;
import com.jannetai.backend.dto.dashboard.KpiTilesResponse;
import com.jannetai.backend.dto.dashboard.WardHeatmapPointResponse;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.repository.DepartmentRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 16 (Analytics Module, SRS 15.14 Business Rules: "trend
 * predictions are refreshed on a configurable schedule (default
 * nightly) rather than computed on every dashboard load, to protect
 * performance"; Government Dashboard, SRS 15.10 Exceptions: "if
 * real-time aggregation service is degraded, the dashboard falls back
 * to the last successfully cached aggregate with a visible 'data as of
 * [timestamp]' notice").
 *
 * DESIGN: an in-process cache (no Redis/external cache store exists in
 * this project - ARCHITECTURE.md Section 7's non-goal against
 * introducing a cache layer "unless a real, demonstrated technical
 * requirement forces it"; a nightly-refreshed, single-instance civic-
 * complaint dashboard does not need one). One {@link DashboardSnapshot}
 * per department (keyed by {@code departmentId}), plus one jurisdiction-
 * wide snapshot ({@code departmentId == null}, stored separately since
 * it additionally carries the department-comparison chart and Admin
 * summary that only make sense unscoped). A snapshot is refreshed:
 * <ul>
 *   <li>Once at application startup ({@link #primeCache()}) - so the
 *       dashboard is never actually empty before the first nightly run,
 *       which is important in a demo/sandbox environment restarted
 *       often (this workspace has no persistent cache across JVM
 *       restarts by construction);</li>
 *   <li>On the configured nightly schedule ({@link #refreshAll()},
 *       {@code app.analytics.aggregation-cron}, default 2 AM daily -
 *       same "no SRS-given number, documented placeholder" convention
 *       as {@code EscalationSchedulerService.sweepIntervalMs});</li>
 *   <li>Lazily, on first-ever request for a department that has no
 *       cached snapshot yet (e.g. a department created after the last
 *       refresh) - see {@link #getSnapshot}.</li>
 * </ul>
 *
 * DEGRADED-FALLBACK BEHAVIOR (the SRS 15.10 Exceptions clause, made
 * real): {@link #refreshAll()} computes every snapshot into new local
 * variables first and only replaces the published cache references at
 * the very end, inside a try/catch - if aggregation throws partway
 * through (e.g. a transient DB issue), the previously published,
 * already-good cache is left completely untouched and continues being
 * served, with its original (older) {@code dataAsOf} timestamp intact.
 * This is exactly SRS 15.10's "falls back to the last successfully
 * cached aggregate with a visible 'data as of [timestamp]' notice" -
 * the timestamp visible to the caller IS the notice.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsCacheService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsCacheService.class);

    private final AnalyticsAggregationService aggregationService;
    private final DepartmentRepository departmentRepository;

    private final Map<Long, DashboardSnapshot> departmentSnapshots = new ConcurrentHashMap<>();
    private final AtomicReference<DashboardSnapshot> jurisdictionSnapshot = new AtomicReference<>();
    private final AtomicReference<List<DepartmentComparisonResponse>> departmentComparisonCache = new AtomicReference<>();
    private final AtomicReference<AdminDashboardSummaryResponse> adminSummaryCache = new AtomicReference<>();

    /** One department's (or the jurisdiction's) cached dashboard data - everything {@code GovernmentDashboardResponse} needs. */
    public record DashboardSnapshot(
            KpiTilesResponse kpis,
            List<WardHeatmapPointResponse> heatmap,
            List<CategoryTrendPointResponse> categoryTrend,
            LocalDateTime generatedAt,
            // Remaining-gaps item 10 (SRS 15.14): computed with the snapshot, i.e.
            // refreshed on the analytics schedule, not on every dashboard load.
            List<CategoryForecastResponse> categoryForecast) {
    }

    @PostConstruct
    public void primeCache() {
        refreshAll();
    }

    /**
     * Runs on {@code app.analytics.aggregation-cron} (default: 2 AM
     * daily). Trend window is the trailing 90 days - SRS 15.10/15.14
     * never specify a fixed lookback window for the dashboard/trend
     * charts (only the separate Reports module's date-range picker is
     * mentioned, SRS 15.12 "date range... default maximum 1 year" for
     * exports, a different concern) - documented placeholder, same
     * "no SRS-given number" convention as every other undocumented
     * numeric default in this project.
     */
    @Scheduled(cron = "${app.analytics.aggregation-cron}")
    public void refreshAll() {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime windowStart = now.minusDays(90);

            Map<Long, DashboardSnapshot> newDepartmentSnapshots = new ConcurrentHashMap<>();
            for (Department department : departmentRepository.findByIsActiveTrueOrderByNameAsc()) {
                newDepartmentSnapshots.put(department.getDepartmentId(), computeSnapshot(
                        department.getDepartmentId(), windowStart, now));
            }
            DashboardSnapshot newJurisdictionSnapshot = computeSnapshot(null, windowStart, now);
            List<DepartmentComparisonResponse> newComparison = aggregationService.computeDepartmentComparison();
            AdminDashboardSummaryResponse newAdminSummary = aggregationService.computeAdminSummary();

            // Publish atomically, only after every computation above succeeded.
            departmentSnapshots.putAll(newDepartmentSnapshots);
            jurisdictionSnapshot.set(newJurisdictionSnapshot);
            departmentComparisonCache.set(newComparison);
            adminSummaryCache.set(newAdminSummary);

            log.info("Analytics aggregation refresh complete: {} department snapshot(s) + jurisdiction-wide snapshot",
                    newDepartmentSnapshots.size());
        } catch (Exception e) {
            // SRS 15.10 Exceptions: keep serving whatever was already cached (possibly nothing,
            // on a first-ever startup failure - see getSnapshot's lazy-compute fallback for that case).
            log.error("Analytics aggregation refresh failed - continuing to serve the previous cached "
                    + "snapshot(s), if any", e);
        }
    }

    /**
     * Returns the cached snapshot for {@code departmentId} ({@code null}
     * = jurisdiction-wide), computing it synchronously on the spot if
     * nothing has ever been cached for it yet (first request after
     * startup races the {@link #primeCache()} call, or a department
     * created after the last refresh with no entry yet - not the SRS
     * 15.10 "degraded" case, which is instead {@link #refreshAll()}
     * silently keeping stale-but-present data).
     */
    public DashboardSnapshot getSnapshot(Long departmentId) {
        if (departmentId == null) {
            DashboardSnapshot cached = jurisdictionSnapshot.get();
            if (cached != null) {
                return cached;
            }
            DashboardSnapshot computed = computeSnapshot(null, LocalDateTime.now().minusDays(90), LocalDateTime.now());
            jurisdictionSnapshot.compareAndSet(null, computed);
            return jurisdictionSnapshot.get();
        }
        return departmentSnapshots.computeIfAbsent(departmentId,
                id -> computeSnapshot(id, LocalDateTime.now().minusDays(90), LocalDateTime.now()));
    }

    public List<DepartmentComparisonResponse> getDepartmentComparison() {
        List<DepartmentComparisonResponse> cached = departmentComparisonCache.get();
        if (cached != null) {
            return cached;
        }
        List<DepartmentComparisonResponse> computed = aggregationService.computeDepartmentComparison();
        departmentComparisonCache.compareAndSet(null, computed);
        return departmentComparisonCache.get();
    }

    public AdminDashboardSummaryResponse getAdminSummary() {
        AdminDashboardSummaryResponse cached = adminSummaryCache.get();
        if (cached != null) {
            return cached;
        }
        AdminDashboardSummaryResponse computed = aggregationService.computeAdminSummary();
        adminSummaryCache.compareAndSet(null, computed);
        return adminSummaryCache.get();
    }

    private DashboardSnapshot computeSnapshot(Long departmentId, LocalDateTime since, LocalDateTime until) {
        KpiTilesResponse kpis = aggregationService.computeKpiTiles(departmentId);
        List<WardHeatmapPointResponse> heatmap = aggregationService.aggregateHeatmap(departmentId, since, until);
        List<CategoryTrendPointResponse> trend = aggregationService.aggregateCategoryTrend(departmentId, since, until);
        List<CategoryForecastResponse> forecast = TrendForecaster.forecast(trend, until.toLocalDate());
        return new DashboardSnapshot(kpis, heatmap, trend, LocalDateTime.now(), forecast);
    }
}
