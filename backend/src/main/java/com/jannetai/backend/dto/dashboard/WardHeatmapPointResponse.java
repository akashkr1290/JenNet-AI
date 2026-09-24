package com.jannetai.backend.dto.dashboard;

/**
 * Phase 16 (Government Dashboard Module, SRS 15.10 Features: "heatmap of
 * complaint density"; SRS 16.3 UI Components: "heatmap"; SRS 24.4 Charts:
 * "ward-level heatmap"). One row per ward that has at least one complaint
 * in the scoped result set - a ward with zero complaints is simply
 * absent, not returned with a zero count (see
 * {@code AnalyticsAggregationService#aggregateHeatmap}'s Javadoc for why).
 *
 * NOT a geographic heatmap: {@link com.jannetai.backend.entity.Ward#getBoundaryGeojson()}
 * is stored but - per that field's own Javadoc ("Not yet consumed") and
 * ARCHITECTURE.md Section 7's non-goal against building a map-rendering
 * layer this project doesn't otherwise need - never consumed anywhere in
 * this codebase. This is a ward-bucketed density table (ward name +
 * count), which is what every existing screen in this codebase (e.g.
 * Phase 13's officer/department tables) already renders as - a real
 * geographic heat-map overlay is a Flutter-side map-rendering concern
 * with no existing precedent in this project and is left for a future
 * phase if a real map view is ever built (documented, not silently
 * dropped).
 */
public record WardHeatmapPointResponse(
        Long wardId,
        String wardName,
        Long complaintCount,
        Long openComplaintCount
) {
}
