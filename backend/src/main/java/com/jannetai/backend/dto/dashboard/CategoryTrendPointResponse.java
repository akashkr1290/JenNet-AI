package com.jannetai.backend.dto.dashboard;

import com.jannetai.backend.entity.enums.ComplaintCategory;

import java.time.LocalDate;

/**
 * Phase 16 (Government Dashboard Module, SRS 15.10 Features: "trend
 * charts by category and time period"; SRS 24.4 Charts: "category trend
 * over time"). One row per (category, day) bucket that had at least one
 * complaint submitted, within whatever date range/department scope the
 * caller requested - see {@code AnalyticsAggregationService#aggregateCategoryTrend}'s
 * Javadoc for the day-bucketing approach and why it's computed in Java
 * rather than a SQL {@code GROUP BY DATE(...)}.
 */
public record CategoryTrendPointResponse(
        ComplaintCategory category,
        LocalDate bucketDate,
        Long count
) {
}
