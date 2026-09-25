package com.jannetai.backend.service.report;

import com.jannetai.backend.dto.report.PeriodReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit GAP-039 (SRS 15.12 / 21 / US-10): period rules, figures and the
 * stored form. The same checks run in the Phase 06 pure-JDK harness.
 * NOT EXECUTED here via Maven.
 */
class PeriodReportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28); // a Monday

    @Test
    void weeklyIsThePriorSevenDaysAndDailyIsYesterday() {
        var weekly = ReportPeriods.resolve(ReportPeriods.Type.WEEKLY, null, null, TODAY, 366);
        assertThat(weekly.start()).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(weekly.end()).isEqualTo(LocalDate.of(2026, 9, 27));
        var daily = ReportPeriods.resolve(ReportPeriods.Type.DAILY, null, null, TODAY, 366);
        assertThat(daily.start()).isEqualTo(daily.end()).isEqualTo(LocalDate.of(2026, 9, 27));
    }

    @Test
    void rangesAreValidated() {
        assertThatThrownBy(() -> ReportPeriods.resolve(ReportPeriods.Type.CUSTOM, null, TODAY, TODAY, 366))
                .hasMessageContaining("both");
        assertThatThrownBy(() -> ReportPeriods.resolve(ReportPeriods.Type.CUSTOM, TODAY, TODAY.minusDays(1), TODAY, 366))
                .hasMessageContaining("before");
        assertThatThrownBy(() -> ReportPeriods.resolve(ReportPeriods.Type.CUSTOM, TODAY, TODAY.plusDays(1), TODAY, 366))
                .hasMessageContaining("future");
        assertThatThrownBy(() -> ReportPeriods.resolve(ReportPeriods.Type.CUSTOM, TODAY.minusDays(366), TODAY, TODAY, 366))
                .hasMessageContaining("maximum");
        assertThat(ReportPeriods.resolve(ReportPeriods.Type.CUSTOM, TODAY.minusDays(365), TODAY, TODAY, 366)).isNotNull();
    }

    @Test
    void istDayBoundariesBecomeUtcQueryBounds() {
        var p = new ReportPeriods.Period(ReportPeriods.Type.DAILY, LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 27));
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        assertThat(ReportPeriods.utcStart(p, ist)).isEqualTo(LocalDateTime.of(2026, 9, 26, 18, 30));
        assertThat(ReportPeriods.utcEndExclusive(p, ist)).isEqualTo(LocalDateTime.of(2026, 9, 27, 18, 30));
        assertThat(ReportPeriods.isClosed(p, TODAY)).isTrue();
    }

    @Test
    void figuresAreComputedFromFactsInThePeriod() {
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 22, 10, 0);
        var complaints = List.of(
                new PeriodReportCalculator.ComplaintFact(1, "POTHOLE", 10L, "Ward A", 100L),
                new PeriodReportCalculator.ComplaintFact(2, "POTHOLE", 10L, "Ward A", 100L),
                new PeriodReportCalculator.ComplaintFact(3, "GARBAGE", 11L, "Ward B", 101L));
        var transitions = List.of(
                new PeriodReportCalculator.TransitionFact(1, "RESOLVED", "POTHOLE", t0.plusHours(10), t0, t0.plusHours(24)),
                new PeriodReportCalculator.TransitionFact(2, "RESOLVED", "POTHOLE", t0.plusHours(30), t0, t0.plusHours(24)),
                new PeriodReportCalculator.TransitionFact(3, "ESCALATED", "GARBAGE", t0.plusHours(5), t0, null));
        var budgets = List.of(
                new PeriodReportCalculator.BudgetFact(1, "POTHOLE", new BigDecimal("1000"), new BigDecimal("2000"), "APPROVED"),
                new PeriodReportCalculator.BudgetFact(2, "POTHOLE", new BigDecimal("500"), new BigDecimal("900"), "PENDING"));
        PeriodReport r = PeriodReportCalculator.calculate("WEEKLY", LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27),
                "Asia/Kolkata", 1L, "Roads", t0, 1, complaints, transitions, budgets, List.of(4, 5));

        assertThat(r.counts().received()).isEqualTo(3);
        assertThat(r.counts().resolved()).isEqualTo(2);
        assertThat(r.counts().escalated()).isEqualTo(1);
        assertThat(r.sla().resolvedWithDeadline()).isEqualTo(2);
        assertThat(r.sla().resolvedWithinDeadline()).isEqualTo(1);
        assertThat(r.sla().compliancePercent()).isEqualTo(50.0);
        assertThat(r.averageResolutionHours()).isEqualTo(20.0);
        assertThat(r.byWard().get(0).wardName()).isEqualTo("Ward A");
        assertThat(r.budget().get(0).approvedMaxTotal()).isEqualByComparingTo("2000");
        assertThat(r.budget().get(0).estimatedMinTotal()).isEqualByComparingTo("1500");
        assertThat(r.engagement().distinctCitizens()).isEqualTo(2);
        assertThat(r.engagement().averageRating()).isEqualTo(4.5);
        assertThat(r.insufficientData()).isFalse();
    }

    @Test
    void anEmptyPeriodIsLabelledInsufficientDataAndSurvivesTheStoredForm() {
        PeriodReport r = PeriodReportCalculator.calculate("DAILY", TODAY.minusDays(1), TODAY.minusDays(1), "Asia/Kolkata",
                null, null, LocalDateTime.of(2026, 9, 28, 2, 30), 1, List.of(), List.of(), List.of(), List.of());
        assertThat(r.insufficientData()).isTrue();
        assertThat(PeriodReportCodec.toCsv(r)).contains("INSUFFICIENT DATA");
        assertThat(PeriodReportCodec.fromJson(PeriodReportCodec.toJson(r))).isEqualTo(r);
    }

    @Test
    void csvQuotesAndNeutralisesFormulas() {
        assertThat(PeriodReportCodec.quote("a,b")).isEqualTo("\"a,b\"");
        assertThat(PeriodReportCodec.quote("=cmd()")).isEqualTo("'=cmd()");
        assertThat(PeriodReportCodec.quote("-12.5")).isEqualTo("-12.5");
    }
}
