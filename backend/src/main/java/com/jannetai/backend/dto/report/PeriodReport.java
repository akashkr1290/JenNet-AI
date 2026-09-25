package com.jannetai.backend.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Audit GAP-039 (SRS 15.12 / 21): one period report. Every figure is computed
 * from the immutable record of what happened inside the period (complaint
 * creation time, status_history transition times), so regenerating a past
 * period gives the same numbers; and each generated report is also stored as a
 * snapshot (report_snapshots, V28) that is served again instead of recomputed.
 *
 * {@code insufficientData} is the SRS 15.12 "clearly labeled 'insufficient
 * data' report": fewer complaints were received in the period than
 * {@code app.reports.min-complaints}.
 */
public record PeriodReport(
        String reportType,
        LocalDate periodStart,
        LocalDate periodEnd,
        String timeZone,
        Long departmentId,
        String departmentName,
        LocalDateTime generatedAt,
        Long snapshotId,
        boolean insufficientData,
        int minimumComplaints,
        Counts counts,
        Sla sla,
        Double averageResolutionHours,
        List<CategoryRow> byCategory,
        List<WardRow> byWard,
        List<BudgetRow> budget,
        Engagement engagement
) {

    /** Complaints received in the period, and status transitions that happened in it. */
    public record Counts(long received, long verified, long assigned, long resolved, long closed,
                         long rejected, long escalated) {
    }

    /**
     * Resolutions in the period that had an SLA deadline, and how many were
     * resolved on or before it. {@code compliancePercent} is null when none had one.
     */
    public record Sla(long resolvedWithDeadline, long resolvedWithinDeadline, Double compliancePercent) {
    }

    public record CategoryRow(String category, long received, long resolved) {
    }

    /** Wards ordered by complaints received (most first): the "top recurring issue locations". */
    public record WardRow(Long wardId, String wardName, long received) {
    }

    /**
     * SRS 21 Budget Reports: "estimated vs. approved cost ranges". Estimates are
     * the prediction service's cost ranges for complaints received in the period;
     * "approved" sums the ranges whose Department Head approval is APPROVED
     * (approval status as at generation time).
     */
    public record BudgetRow(String category, long estimates, BigDecimal estimatedMinTotal, BigDecimal estimatedMaxTotal,
                            long approved, BigDecimal approvedMinTotal, BigDecimal approvedMaxTotal,
                            long rejected, long pending) {
    }

    /** SRS 21 Citizen Reports, aggregate only (no personal data). */
    public record Engagement(long distinctCitizens, long ratings, Double averageRating) {
    }
}
