package com.jannetai.backend.service.department;

import java.time.LocalDateTime;

/**
 * Audit GAP-027: the SLA window of one complaint, fixed when its clock starts.
 * Pure JDK (testable without Spring). The warning point is 80 % of the window
 * (SRS 15.13 "SLA-breach warnings at 80%"), rounded to the minute.
 */
public record SlaWindow(LocalDateTime startedAt, int hours, LocalDateTime warningAt, LocalDateTime dueAt) {

    public static final double WARNING_FRACTION = 0.8;

    public static SlaWindow starting(LocalDateTime startedAt, long hours) {
        if (startedAt == null || hours < 1) {
            throw new IllegalArgumentException("an SLA window needs a start time and at least 1 hour");
        }
        return new SlaWindow(startedAt, (int) hours,
                startedAt.plusMinutes(Math.round(hours * 60 * WARNING_FRACTION)),
                startedAt.plusHours(hours));
    }

    public boolean isBreachedAt(LocalDateTime now) {
        return !now.isBefore(dueAt);
    }

    public boolean isInWarningPeriodAt(LocalDateTime now) {
        return !now.isBefore(warningAt) && now.isBefore(dueAt);
    }
}
