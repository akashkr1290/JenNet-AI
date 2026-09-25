package com.jannetai.backend.service.report;

import com.jannetai.backend.dto.report.PeriodReport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Audit GAP-039: the arithmetic of a {@link PeriodReport}, over plain facts
 * (no JPA types) so it can be unit-tested in isolation.
 * {@link PeriodReportService} loads the facts for the period and scope.
 */
public final class PeriodReportCalculator {

    /** A complaint received (created) in the period. */
    public record ComplaintFact(long complaintId, String category, Long wardId, String wardName, Long citizenId) {
    }

    /** A status transition that happened in the period. */
    public record TransitionFact(long complaintId, String newStatus, String category, LocalDateTime changedAt,
                                 LocalDateTime complaintCreatedAt, LocalDateTime slaDueAt) {
    }

    /** The latest cost estimate of a complaint received in the period. */
    public record BudgetFact(long complaintId, String category, BigDecimal min, BigDecimal max, String approvalStatus) {
    }

    private PeriodReportCalculator() {
    }

    public static PeriodReport calculate(String reportType, LocalDate start, LocalDate end, String zone,
                                         Long departmentId, String departmentName, LocalDateTime generatedAt,
                                         int minimumComplaints,
                                         List<ComplaintFact> complaints, List<TransitionFact> transitions,
                                         List<BudgetFact> budgets, List<Integer> ratings) {
        Map<String, Long> transitionCounts = new HashMap<>();
        for (TransitionFact t : transitions) {
            transitionCounts.merge(t.newStatus(), 1L, Long::sum);
        }
        PeriodReport.Counts counts = new PeriodReport.Counts(complaints.size(),
                transitionCounts.getOrDefault("VERIFIED", 0L), transitionCounts.getOrDefault("ASSIGNED", 0L),
                transitionCounts.getOrDefault("RESOLVED", 0L), transitionCounts.getOrDefault("CLOSED", 0L),
                transitionCounts.getOrDefault("REJECTED", 0L), transitionCounts.getOrDefault("ESCALATED", 0L));

        // SLA + resolution time: first RESOLVED transition per complaint within the period
        Map<Long, TransitionFact> firstResolution = new LinkedHashMap<>();
        transitions.stream()
                .filter(t -> "RESOLVED".equals(t.newStatus()))
                .sorted(Comparator.comparing(TransitionFact::changedAt))
                .forEach(t -> firstResolution.putIfAbsent(t.complaintId(), t));
        long withDeadline = 0;
        long withinDeadline = 0;
        double hoursSum = 0;
        long hoursCount = 0;
        for (TransitionFact t : firstResolution.values()) {
            if (t.slaDueAt() != null) {
                withDeadline++;
                if (!t.changedAt().isAfter(t.slaDueAt())) {
                    withinDeadline++;
                }
            }
            if (t.complaintCreatedAt() != null && !t.changedAt().isBefore(t.complaintCreatedAt())) {
                hoursSum += Duration.between(t.complaintCreatedAt(), t.changedAt()).toMinutes() / 60.0;
                hoursCount++;
            }
        }
        PeriodReport.Sla sla = new PeriodReport.Sla(withDeadline, withinDeadline,
                withDeadline == 0 ? null : round1(100.0 * withinDeadline / withDeadline));
        Double avgHours = hoursCount == 0 ? null : round1(hoursSum / hoursCount);

        // by category: received (complaints) and resolved (first resolutions)
        Map<String, long[]> categories = new TreeMap<>();
        for (ComplaintFact c : complaints) {
            categories.computeIfAbsent(label(c.category()), k -> new long[2])[0]++;
        }
        for (TransitionFact t : firstResolution.values()) {
            categories.computeIfAbsent(label(t.category()), k -> new long[2])[1]++;
        }
        List<PeriodReport.CategoryRow> byCategory = new ArrayList<>();
        categories.forEach((k, v) -> byCategory.add(new PeriodReport.CategoryRow(k, v[0], v[1])));

        // by ward, most complaints first (ties by name)
        Map<Long, String> wardNames = new HashMap<>();
        Map<Long, Long> wardCounts = new HashMap<>();
        for (ComplaintFact c : complaints) {
            if (c.wardId() != null) {
                wardNames.putIfAbsent(c.wardId(), c.wardName());
                wardCounts.merge(c.wardId(), 1L, Long::sum);
            }
        }
        List<PeriodReport.WardRow> byWard = new ArrayList<>();
        wardCounts.forEach((id, n) -> byWard.add(new PeriodReport.WardRow(id, wardNames.get(id), n)));
        byWard.sort(Comparator.comparingLong(PeriodReport.WardRow::received).reversed()
                .thenComparing(w -> Objects.toString(w.wardName(), "")));

        // budget: estimated vs approved cost ranges by category
        Map<String, BudgetAcc> budgetAcc = new TreeMap<>();
        for (BudgetFact b : budgets) {
            BudgetAcc acc = budgetAcc.computeIfAbsent(label(b.category()), k -> new BudgetAcc());
            acc.estimates++;
            acc.min = acc.min.add(nz(b.min()));
            acc.max = acc.max.add(nz(b.max()));
            switch (Objects.toString(b.approvalStatus(), "PENDING")) {
                case "APPROVED" -> {
                    acc.approved++;
                    acc.approvedMin = acc.approvedMin.add(nz(b.min()));
                    acc.approvedMax = acc.approvedMax.add(nz(b.max()));
                }
                case "REJECTED" -> acc.rejected++;
                default -> acc.pending++;
            }
        }
        List<PeriodReport.BudgetRow> budgetRows = new ArrayList<>();
        budgetAcc.forEach((k, a) -> budgetRows.add(new PeriodReport.BudgetRow(k, a.estimates, a.min, a.max,
                a.approved, a.approvedMin, a.approvedMax, a.rejected, a.pending)));

        Set<Long> citizens = new HashSet<>();
        for (ComplaintFact c : complaints) {
            if (c.citizenId() != null) {
                citizens.add(c.citizenId());
            }
        }
        double ratingSum = 0;
        for (Integer r : ratings) {
            ratingSum += r;
        }
        PeriodReport.Engagement engagement = new PeriodReport.Engagement(citizens.size(), ratings.size(),
                ratings.isEmpty() ? null : round1(ratingSum / ratings.size()));

        boolean insufficient = complaints.size() < Math.max(minimumComplaints, 1);
        return new PeriodReport(reportType, start, end, zone, departmentId, departmentName, generatedAt, null,
                insufficient, Math.max(minimumComplaints, 1), counts, sla, avgHours, byCategory, byWard, budgetRows,
                engagement);
    }

    private static final class BudgetAcc {
        long estimates;
        long approved;
        long rejected;
        long pending;
        BigDecimal min = BigDecimal.ZERO;
        BigDecimal max = BigDecimal.ZERO;
        BigDecimal approvedMin = BigDecimal.ZERO;
        BigDecimal approvedMax = BigDecimal.ZERO;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String label(String category) {
        return category == null ? "UNCLASSIFIED" : category;
    }

    static Double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
