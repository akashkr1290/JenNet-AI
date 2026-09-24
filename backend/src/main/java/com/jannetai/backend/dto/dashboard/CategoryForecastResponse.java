package com.jannetai.backend.dto.dashboard;

import com.jannetai.backend.entity.enums.ComplaintCategory;

/**
 * Remaining-gaps item 10 (SRS 15.14 "trend predictions"): next-7-days
 * complaint-volume outlook for one category, computed by
 * {@link com.jannetai.backend.service.dashboard.TrendForecaster} during the
 * scheduled analytics refresh.
 *
 * {@code status} is FORECAST or INSUFFICIENT_HISTORY. When history is
 * insufficient, the three numeric forecast fields are null - no number is
 * produced rather than a guessed one. {@code lower80}/{@code upper80} are an
 * approximate 80% prediction interval under the method's own assumptions
 * (linear trend, roughly normal residuals); no accuracy figure is claimed.
 */
public record CategoryForecastResponse(
        ComplaintCategory category,
        String status,
        String method,
        int weeksOfHistory,
        long lastWeekCount,
        Double trendPerWeek,
        Double forecastNext7Days,
        Double lower80,
        Double upper80
) {
}
