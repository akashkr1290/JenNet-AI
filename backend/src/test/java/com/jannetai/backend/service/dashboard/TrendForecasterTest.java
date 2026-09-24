package com.jannetai.backend.service.dashboard;

import com.jannetai.backend.dto.dashboard.CategoryForecastResponse;
import com.jannetai.backend.dto.dashboard.CategoryTrendPointResponse;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Remaining-gaps item 10: SRS 15.14 trend predictions (pure-function unit tests). */
class TrendForecasterTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 24);

    /** One point per week, `perWeek[i]` complaints, i = 0 oldest ... last = the week ending AS_OF. */
    private static List<CategoryTrendPointResponse> weekly(ComplaintCategory c, long... perWeek) {
        List<CategoryTrendPointResponse> out = new ArrayList<>();
        for (int i = 0; i < perWeek.length; i++) {
            int weeksBack = perWeek.length - 1 - i;
            if (perWeek[i] > 0) {
                out.add(new CategoryTrendPointResponse(c, AS_OF.minusDays(7L * weeksBack), perWeek[i]));
            }
        }
        return out;
    }

    @Test
    void perfectlyLinearHistoryProjectsTheNextStepWithZeroWidthInterval() {
        List<CategoryForecastResponse> r = TrendForecaster.forecast(
                weekly(ComplaintCategory.POTHOLE, 2, 4, 6, 8, 10, 12, 14, 16), AS_OF);
        assertEquals(1, r.size());
        CategoryForecastResponse f = r.get(0);
        assertEquals(TrendForecaster.STATUS_FORECAST, f.status());
        assertEquals(8, f.weeksOfHistory());
        assertEquals(2.0, f.trendPerWeek());
        assertEquals(18.0, f.forecastNext7Days());
        assertEquals(18.0, f.lower80());
        assertEquals(18.0, f.upper80());
        assertEquals(16, f.lastWeekCount());
    }

    @Test
    void refusesToForecastWithTooFewWeeks() {
        CategoryForecastResponse f = TrendForecaster.forecast(
                weekly(ComplaintCategory.GARBAGE_OVERFLOW, 5, 5, 5), AS_OF).get(0);
        assertEquals(TrendForecaster.STATUS_INSUFFICIENT, f.status());
        assertNull(f.forecastNext7Days());
        assertNull(f.lower80());
        assertNull(f.upper80());
    }

    @Test
    void refusesToForecastWithTooFewComplaints() {
        CategoryForecastResponse f = TrendForecaster.forecast(
                weekly(ComplaintCategory.WATER_LEAKAGE, 1, 0, 1, 0, 1, 0, 1, 0), AS_OF).get(0);
        assertEquals(TrendForecaster.STATUS_INSUFFICIENT, f.status());
        assertNull(f.forecastNext7Days());
    }

    @Test
    void steeplyFallingTrendIsClampedAtZeroAndIntervalIsOrdered() {
        CategoryForecastResponse f = TrendForecaster.forecast(
                weekly(ComplaintCategory.OPEN_MANHOLE, 30, 25, 18, 12, 7, 3, 1, 0), AS_OF).get(0);
        assertEquals(TrendForecaster.STATUS_FORECAST, f.status());
        assertEquals(0.0, f.forecastNext7Days());
        assertEquals(0.0, f.lower80());
        assertTrue(f.upper80() >= f.forecastNext7Days());
        assertTrue(f.trendPerWeek() < 0);
    }

    @Test
    void noisyHistoryGivesAPositiveWidthIntervalAroundThePoint() {
        CategoryForecastResponse f = TrendForecaster.forecast(
                weekly(ComplaintCategory.POTHOLE, 10, 14, 9, 15, 11, 16, 12, 17), AS_OF).get(0);
        assertTrue(f.lower80() < f.forecastNext7Days());
        assertTrue(f.upper80() > f.forecastNext7Days());
    }

    @Test
    void categoriesAreIndependentSortedAndOldOrFutureBucketsIgnored() {
        List<CategoryTrendPointResponse> points = new ArrayList<>(weekly(ComplaintCategory.POTHOLE, 3, 3, 3, 3, 3, 3));
        points.addAll(weekly(ComplaintCategory.BROKEN_STREET_LIGHT, 2, 2, 2, 2, 2, 2));
        points.add(new CategoryTrendPointResponse(ComplaintCategory.POTHOLE, AS_OF.minusDays(200), 999L));
        points.add(new CategoryTrendPointResponse(ComplaintCategory.POTHOLE, AS_OF.plusDays(3), 999L));
        List<CategoryForecastResponse> r = TrendForecaster.forecast(points, AS_OF);
        assertEquals(2, r.size());
        assertEquals(ComplaintCategory.BROKEN_STREET_LIGHT, r.get(0).category());
        assertEquals(ComplaintCategory.POTHOLE, r.get(1).category());
        assertEquals(3.0, r.get(1).forecastNext7Days());
    }

    @Test
    void emptyOrNullInputProducesNoForecasts() {
        assertTrue(TrendForecaster.forecast(List.of(), AS_OF).isEmpty());
        assertTrue(TrendForecaster.forecast(null, AS_OF).isEmpty());
    }
}
