package com.jannetai.backend.controller;

import com.jannetai.backend.dto.dashboard.WardHeatmapPointResponse;
import com.jannetai.backend.service.dashboard.AnalyticsCacheService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Gap-backlog Patch 10/12 (Sep 2026 audit): "a public/community-level
 * heatmap is not fully implemented as a dedicated feature" -
 * {@code GovernmentDashboardController}'s {@code /overview} already
 * computed this exact data (via {@link AnalyticsCacheService}) but
 * gated it behind DEPARTMENT_HEAD/ADMIN/SUPER_ADMIN, alongside KPIs and
 * an admin summary a citizen has no business seeing. This is a separate,
 * narrower endpoint exposing ONLY the ward-bucketed density table
 * ({@link WardHeatmapPointResponse} - already documented privacy-safe:
 * ward name + counts, no individual complaint or citizen data - see that
 * record's own Javadoc) to any authenticated role, not just staff.
 *
 * Jurisdiction-wide only (no {@code departmentId} filter) - a per-
 * department view is a staff concern
 * ({@code GovernmentDashboardController}'s own {@code departmentId}
 * param), not something a citizen-facing community view needs.
 */
@RestController
@RequestMapping("/api/v1/community")
@RequiredArgsConstructor
@Tag(name = "Community Heatmap", description = "Citizen-visible ward-level complaint density (Gap-backlog Patch 10/12)")
public class CommunityHeatmapController {

    private final AnalyticsCacheService analyticsCacheService;

    @GetMapping("/heatmap")
    @PreAuthorize("isAuthenticated()")
    public List<WardHeatmapPointResponse> heatmap() {
        return analyticsCacheService.getSnapshot(null).heatmap();
    }
}
