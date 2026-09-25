package com.jannetai.backend.service.report;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Audit GAP-039 (SRS 15.12 "Inputs: date range ... report type"; "date ranges
 * are validated ... configurable maximum range, default 1 year"; SRS 21 Daily =
 * "last 24 hours", Weekly = "7-day"; US-10 "the prior 7 days").
 *
 * Dates are calendar days in the reporting zone (app.reports.zone,
 * Asia/Kolkata by default); both ends are inclusive. Timestamps in the database
 * are UTC (Phase 01 UTC pin), so {@link #utcStart}/{@link #utcEndExclusive}
 * convert the period to UTC bounds for queries. Pure JDK.
 */
public final class ReportPeriods {

    public enum Type { DAILY, WEEKLY, CUSTOM }

    public record Period(Type type, LocalDate start, LocalDate end) {
    }

    private ReportPeriods() {
    }

    /**
     * @param today    "today" in the reporting zone
     * @param from/to  required for CUSTOM; for DAILY an optional day (default yesterday);
     *                 for WEEKLY an optional last day (default yesterday) - the 7 days ending on it
     * @throws IllegalArgumentException (HTTP 400) for a missing, inverted, future or too-long range
     */
    public static Period resolve(Type type, LocalDate from, LocalDate to, LocalDate today, int maxRangeDays) {
        Type t = type == null ? Type.CUSTOM : type;
        LocalDate yesterday = today.minusDays(1);
        Period period = switch (t) {
            case DAILY -> {
                LocalDate day = to != null ? to : (from != null ? from : yesterday);
                yield new Period(t, day, day);
            }
            case WEEKLY -> {
                LocalDate last = to != null ? to : yesterday;
                yield new Period(t, last.minusDays(6), last);
            }
            case CUSTOM -> {
                if (from == null || to == null) {
                    throw new IllegalArgumentException("A custom report needs both 'from' and 'to' dates");
                }
                yield new Period(t, from, to);
            }
        };
        if (period.end().isBefore(period.start())) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }
        if (period.end().isAfter(today)) {
            throw new IllegalArgumentException("The report period cannot end in the future");
        }
        long days = ChronoUnit.DAYS.between(period.start(), period.end()) + 1;
        if (days > maxRangeDays) {
            throw new IllegalArgumentException("The report period is " + days + " days; the maximum is " + maxRangeDays
                    + " days (app.reports.max-range-days)");
        }
        return period;
    }

    public static LocalDateTime utcStart(Period p, ZoneId zone) {
        return p.start().atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    public static LocalDateTime utcEndExclusive(Period p, ZoneId zone) {
        return p.end().plusDays(1).atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** A period whose last day is before today can no longer change: its snapshot may be reused. */
    public static boolean isClosed(Period p, LocalDate today) {
        return p.end().isBefore(today);
    }
}
