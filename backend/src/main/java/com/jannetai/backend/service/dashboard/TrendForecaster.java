package com.jannetai.backend.service.dashboard;

import com.jannetai.backend.dto.dashboard.CategoryForecastResponse;
import com.jannetai.backend.dto.dashboard.CategoryTrendPointResponse;
import com.jannetai.backend.entity.enums.ComplaintCategory;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Remaining-gaps item 10 - SRS 15.14 Business Rules: "trend predictions are
 * refreshed on a configurable schedule (default nightly) rather than
 * computed on every dashboard load". Before this class the Analytics Module
 * only aggregated history (the daily category trend); nothing predicted.
 *
 * <p>Method (deliberately simple, transparent and data-honest - no trained
 * model, no external data): the category's own daily counts from the cached
 * trailing window are summed into complete 7-day weeks ending on the
 * snapshot date, and an ordinary-least-squares linear trend is fitted to the
 * weeks from the category's first non-empty week onward. The projection is
 * the fitted value one week ahead (clamped at zero) with an approximate 80%
 * prediction interval from the residual standard error.
 *
 * <p>Refusal rule: a category needs at least {@value #MIN_WEEKS_OF_HISTORY}
 * weeks of history and at least {@value #MIN_TOTAL_COMPLAINTS} complaints in
 * the window; otherwise it is reported as INSUFFICIENT_HISTORY with no
 * numbers. Pure and stateless - no Spring, no I/O - so it is unit-testable in
 * isolation and is invoked from AnalyticsCacheService's scheduled refresh.
 */
public final class TrendForecaster {

    public static final String METHOD = "OLS_LINEAR_TREND_WEEKLY";
    public static final String STATUS_FORECAST = "FORECAST";
    public static final String STATUS_INSUFFICIENT = "INSUFFICIENT_HISTORY";
    static final int WEEKS_IN_WINDOW = 12;
    static final int MIN_WEEKS_OF_HISTORY = 6;
    static final int MIN_TOTAL_COMPLAINTS = 8;
    private static final double Z_80 = 1.2816;

    private TrendForecaster() {
    }

    public static List<CategoryForecastResponse> forecast(List<CategoryTrendPointResponse> dailyTrend, LocalDate asOf) {
        Map<ComplaintCategory, long[]> weekly = new EnumMap<>(ComplaintCategory.class);
        if (dailyTrend != null && asOf != null) {
            for (CategoryTrendPointResponse point : dailyTrend) {
                if (point == null || point.category() == null || point.bucketDate() == null || point.count() == null) {
                    continue;
                }
                long daysBack = ChronoUnit.DAYS.between(point.bucketDate(), asOf);
                if (daysBack < 0) {
                    continue;
                }
                int weeksBack = (int) (daysBack / 7);
                if (weeksBack >= WEEKS_IN_WINDOW) {
                    continue;
                }
                long[] series = weekly.computeIfAbsent(point.category(), c -> new long[WEEKS_IN_WINDOW]);
                series[WEEKS_IN_WINDOW - 1 - weeksBack] += point.count(); // index 0 = oldest week
            }
        }
        List<CategoryForecastResponse> out = new ArrayList<>();
        for (Map.Entry<ComplaintCategory, long[]> e : weekly.entrySet()) {
            out.add(forecastSeries(e.getKey(), e.getValue()));
        }
        out.sort(Comparator.comparing(r -> r.category().name()));
        return out;
    }

    static CategoryForecastResponse forecastSeries(ComplaintCategory category, long[] series) {
        int first = 0;
        while (first < series.length && series[first] == 0) {
            first++;
        }
        int n = series.length - first;
        long total = 0;
        for (long v : series) {
            total += v;
        }
        long lastWeek = series.length == 0 ? 0 : series[series.length - 1];
        if (n < MIN_WEEKS_OF_HISTORY || total < MIN_TOTAL_COMPLAINTS) {
            return new CategoryForecastResponse(category, STATUS_INSUFFICIENT, METHOD, Math.max(n, 0),
                    lastWeek, null, null, null, null);
        }

        double meanX = (n - 1) / 2.0;
        double meanY = 0;
        for (int i = 0; i < n; i++) {
            meanY += series[first + i];
        }
        meanY /= n;
        double sxx = 0;
        double sxy = 0;
        for (int i = 0; i < n; i++) {
            double dx = i - meanX;
            sxx += dx * dx;
            sxy += dx * (series[first + i] - meanY);
        }
        double slope = sxx == 0 ? 0 : sxy / sxx;
        double intercept = meanY - slope * meanX;

        double ssr = 0;
        for (int i = 0; i < n; i++) {
            double residual = series[first + i] - (intercept + slope * i);
            ssr += residual * residual;
        }
        double residualStdError = n > 2 ? Math.sqrt(ssr / (n - 2)) : 0;
        double x0 = n; // one week beyond the last observed week
        double halfWidth = Z_80 * residualStdError
                * Math.sqrt(1 + 1.0 / n + (sxx == 0 ? 0 : (x0 - meanX) * (x0 - meanX) / sxx));
        double point = Math.max(0, intercept + slope * x0);

        return new CategoryForecastResponse(category, STATUS_FORECAST, METHOD, n, lastWeek,
                round1(slope), round1(point), round1(Math.max(0, point - halfWidth)), round1(point + halfWidth));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
