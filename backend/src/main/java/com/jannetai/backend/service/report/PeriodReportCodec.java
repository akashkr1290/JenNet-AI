package com.jannetai.backend.service.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jannetai.backend.dto.report.PeriodReport;

import java.math.BigDecimal;
import java.util.StringJoiner;

/**
 * Audit GAP-039: stored form (JSON, report_snapshots.payload_json) and CSV
 * export of a {@link PeriodReport}. Uses its own ObjectMapper so the stored
 * format does not change if the web layer's Jackson settings change.
 * Timestamps inside the payload are UTC (Phase 01 UTC pin). No Spring types.
 */
public final class PeriodReportCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private PeriodReportCodec() {
    }

    public static String toJson(PeriodReport report) {
        try {
            return MAPPER.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the report", e);
        }
    }

    public static PeriodReport fromJson(String json) {
        try {
            return MAPPER.readValue(json, PeriodReport.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored report snapshot is unreadable", e);
        }
    }

    /** Sectioned CSV (RFC 4180 quoting); the first lines carry the period, scope and the insufficient-data label. */
    public static String toCsv(PeriodReport r) {
        StringBuilder out = new StringBuilder();
        line(out, "JanNet AI period report");
        line(out, "Report type", r.reportType());
        line(out, "Period", r.periodStart() + " to " + r.periodEnd() + " (" + r.timeZone() + ")");
        line(out, "Scope", r.departmentName() != null ? r.departmentName() : "All departments");
        line(out, "Generated at (UTC)", String.valueOf(r.generatedAt()));
        line(out, "Snapshot id", r.snapshotId() == null ? "" : String.valueOf(r.snapshotId()));
        if (r.insufficientData()) {
            line(out, "INSUFFICIENT DATA", "Fewer than " + r.minimumComplaints()
                    + " complaint(s) were received in this period; figures are not representative.");
        }
        out.append("\r\n");
        line(out, "Measure", "Value");
        PeriodReport.Counts c = r.counts();
        line(out, "Complaints received", String.valueOf(c.received()));
        line(out, "Verified", String.valueOf(c.verified()));
        line(out, "Assigned", String.valueOf(c.assigned()));
        line(out, "Resolved", String.valueOf(c.resolved()));
        line(out, "Closed", String.valueOf(c.closed()));
        line(out, "Rejected", String.valueOf(c.rejected()));
        line(out, "Escalated", String.valueOf(c.escalated()));
        line(out, "Resolutions with an SLA deadline", String.valueOf(r.sla().resolvedWithDeadline()));
        line(out, "Resolved within SLA", String.valueOf(r.sla().resolvedWithinDeadline()));
        line(out, "SLA compliance %", r.sla().compliancePercent() == null ? "" : String.valueOf(r.sla().compliancePercent()));
        line(out, "Average resolution hours", r.averageResolutionHours() == null ? "" : String.valueOf(r.averageResolutionHours()));
        line(out, "Citizens who submitted", String.valueOf(r.engagement().distinctCitizens()));
        line(out, "Ratings given", String.valueOf(r.engagement().ratings()));
        line(out, "Average rating", r.engagement().averageRating() == null ? "" : String.valueOf(r.engagement().averageRating()));
        out.append("\r\n");
        line(out, "Category", "Received", "Resolved");
        for (PeriodReport.CategoryRow row : r.byCategory()) {
            line(out, row.category(), String.valueOf(row.received()), String.valueOf(row.resolved()));
        }
        out.append("\r\n");
        line(out, "Ward", "Received");
        for (PeriodReport.WardRow row : r.byWard()) {
            line(out, row.wardName(), String.valueOf(row.received()));
        }
        out.append("\r\n");
        line(out, "Budget category", "Estimates", "Estimated min (INR)", "Estimated max (INR)", "Approved",
                "Approved min (INR)", "Approved max (INR)", "Rejected", "Pending");
        for (PeriodReport.BudgetRow b : r.budget()) {
            line(out, b.category(), String.valueOf(b.estimates()), money(b.estimatedMinTotal()), money(b.estimatedMaxTotal()),
                    String.valueOf(b.approved()), money(b.approvedMinTotal()), money(b.approvedMaxTotal()),
                    String.valueOf(b.rejected()), String.valueOf(b.pending()));
        }
        return out.toString();
    }

    private static String money(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private static void line(StringBuilder out, String... cells) {
        StringJoiner row = new StringJoiner(",");
        for (String cell : cells) {
            row.add(quote(cell));
        }
        out.append(row).append("\r\n");
    }

    static String quote(String value) {
        String v = value == null ? "" : value;
        // neutralise spreadsheet formula injection (a cell starting with = + - @ that is not a number)
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?")) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
