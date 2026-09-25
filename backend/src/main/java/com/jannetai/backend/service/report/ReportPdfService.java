package com.jannetai.backend.service.report;

import com.jannetai.backend.dto.dashboard.CategoryTrendPointResponse;
import com.jannetai.backend.dto.dashboard.GovernmentDashboardResponse;
import com.jannetai.backend.dto.dashboard.KpiTilesResponse;
import com.jannetai.backend.dto.dashboard.WardHeatmapPointResponse;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.service.dashboard.GovernmentDashboardService;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;

/**
 * Gap-backlog Patch 18 (Sep 2026 strict recheck): PDF version of the
 * Government Dashboard overview (KPIs incl. SLA compliance, ward heatmap,
 * category trend). Built from GovernmentDashboardService#getOverview, so it
 * inherits that method's department scoping exactly - a department head's
 * PDF can only ever contain their own department, same as the CSV export.
 *
 * Audit GAP-039 adds {@link #periodPdf}: the PDF of a {@link com.jannetai.backend.dto.report.PeriodReport}
 * (daily / weekly / custom date range, with SLA, category, ward, budget and
 * citizen-engagement sections), used by GET /api/v1/reports/period.pdf and the
 * weekly Department Head e-mail.
 */
@Service
@RequiredArgsConstructor
public class ReportPdfService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    private final GovernmentDashboardService governmentDashboardService;

    public byte[] overviewPdf(User requester, Long departmentId) {
        GovernmentDashboardResponse report = governmentDashboardService.getOverview(requester, departmentId);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 42, 36);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();
            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font heading = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 10);

            doc.add(new Paragraph("JanNet AI - Complaint Overview Report", title));
            String scope = departmentId != null ? "Department #" + departmentId
                    : (requester.getDepartment() != null ? requester.getDepartment().getName() : "All departments");
            doc.add(new Paragraph("Scope: " + scope + "    Data as of: "
                    + (report.dataAsOf() != null ? report.dataAsOf().format(STAMP) : "-"), body));
            doc.add(new Paragraph(" "));

            KpiTilesResponse k = report.kpis();
            doc.add(new Paragraph("Key figures", heading));
            PdfPTable kpis = table(2, body, "Measure", "Value");
            row(kpis, body, "Total complaints", String.valueOf(k.totalComplaints()));
            row(kpis, body, "Open", String.valueOf(k.openComplaints()));
            row(kpis, body, "Resolved", String.valueOf(k.resolvedComplaints()));
            row(kpis, body, "Escalated (SLA breached)", String.valueOf(k.escalatedComplaints()));
            row(kpis, body, "SLA compliance",
                    k.slaCompliancePercent() == null ? "-" : String.format("%.1f%%", k.slaCompliancePercent()));
            doc.add(kpis);

            doc.add(new Paragraph("Complaints by ward", heading));
            PdfPTable wards = table(3, body, "Ward", "Complaints", "Open");
            for (WardHeatmapPointResponse w : report.heatmap()) {
                row(wards, body, String.valueOf(w.wardName()), String.valueOf(w.complaintCount()),
                        String.valueOf(w.openComplaintCount()));
            }
            doc.add(wards);

            doc.add(new Paragraph("Category trend", heading));
            PdfPTable trend = table(3, body, "Category", "Date", "Count");
            for (CategoryTrendPointResponse t : report.categoryTrend()) {
                row(trend, body, t.category() == null ? "-" : t.category().name().replace('_', ' '),
                        String.valueOf(t.bucketDate()), String.valueOf(t.count()));
            }
            doc.add(trend);
            doc.close();
        } catch (Exception e) {
            if (doc.isOpen()) {
                doc.close();
            }
            throw new IllegalStateException("Could not render overview PDF: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    /** Audit GAP-039: PDF of a period report. An insufficient-data report says so in its first lines. */
    public byte[] periodPdf(com.jannetai.backend.dto.report.PeriodReport r) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 42, 36);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();
            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font heading = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 10);
            Font warning = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, java.awt.Color.RED);

            doc.add(new Paragraph("JanNet AI - " + titleCase(r.reportType()) + " Report", title));
            doc.add(new Paragraph("Period: " + r.periodStart() + " to " + r.periodEnd() + " (" + r.timeZone() + ")"
                    + "    Scope: " + (r.departmentName() != null ? r.departmentName() : "All departments"), body));
            doc.add(new Paragraph("Generated (UTC): " + r.generatedAt()
                    + (r.snapshotId() != null ? "    Snapshot #" + r.snapshotId() : ""), body));
            if (r.insufficientData()) {
                doc.add(new Paragraph("INSUFFICIENT DATA - fewer than " + r.minimumComplaints()
                        + " complaint(s) were received in this period; the figures below are not representative.", warning));
            }
            doc.add(new Paragraph(" "));

            doc.add(new Paragraph("Key figures", heading));
            var c = r.counts();
            PdfPTable kpis = table(2, body, "Measure", "Value");
            row(kpis, body, "Complaints received", String.valueOf(c.received()));
            row(kpis, body, "Verified", String.valueOf(c.verified()));
            row(kpis, body, "Assigned", String.valueOf(c.assigned()));
            row(kpis, body, "Resolved", String.valueOf(c.resolved()));
            row(kpis, body, "Closed", String.valueOf(c.closed()));
            row(kpis, body, "Rejected", String.valueOf(c.rejected()));
            row(kpis, body, "Escalated (SLA breached)", String.valueOf(c.escalated()));
            row(kpis, body, "SLA compliance", r.sla().compliancePercent() == null ? "-"
                    : r.sla().compliancePercent() + "% (" + r.sla().resolvedWithinDeadline() + " of "
                    + r.sla().resolvedWithDeadline() + ")");
            row(kpis, body, "Average resolution (hours)",
                    r.averageResolutionHours() == null ? "-" : String.valueOf(r.averageResolutionHours()));
            row(kpis, body, "Citizens who submitted", String.valueOf(r.engagement().distinctCitizens()));
            row(kpis, body, "Ratings / average", r.engagement().ratings() + " / "
                    + (r.engagement().averageRating() == null ? "-" : r.engagement().averageRating()));
            doc.add(kpis);

            doc.add(new Paragraph("By category", heading));
            PdfPTable cats = table(3, body, "Category", "Received", "Resolved");
            for (var row : r.byCategory()) {
                row(cats, body, row.category().replace('_', ' '), String.valueOf(row.received()), String.valueOf(row.resolved()));
            }
            doc.add(cats);

            doc.add(new Paragraph("Top locations (wards)", heading));
            PdfPTable wards = table(2, body, "Ward", "Received");
            for (var row : r.byWard()) {
                row(wards, body, String.valueOf(row.wardName()), String.valueOf(row.received()));
            }
            doc.add(wards);

            doc.add(new Paragraph("Budget: estimated vs approved (INR)", heading));
            PdfPTable budget = table(5, body, "Category", "Estimates", "Estimated range", "Approved", "Approved range");
            for (var b : r.budget()) {
                row(budget, body, b.category().replace('_', ' '), String.valueOf(b.estimates()),
                        b.estimatedMinTotal().toPlainString() + " - " + b.estimatedMaxTotal().toPlainString(),
                        String.valueOf(b.approved()),
                        b.approvedMinTotal().toPlainString() + " - " + b.approvedMaxTotal().toPlainString());
            }
            doc.add(budget);
            doc.close();
        } catch (Exception e) {
            if (doc.isOpen()) {
                doc.close();
            }
            throw new IllegalStateException("Could not render period PDF: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    private static String titleCase(String type) {
        return type == null || type.isEmpty() ? "Period"
                : type.charAt(0) + type.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private static PdfPTable table(int columns, Font font, String... headers) {
        PdfPTable t = new PdfPTable(columns);
        t.setWidthPercentage(100);
        t.setSpacingBefore(4);
        t.setSpacingAfter(12);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, font.getSize());
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, bold));
            cell.setHorizontalAlignment(Element.ALIGN_LEFT);
            t.addCell(cell);
        }
        t.setHeaderRows(1);
        return t;
    }

    private static void row(PdfPTable t, Font font, String... values) {
        for (String v : values) {
            t.addCell(new PdfPCell(new Phrase(v, font)));
        }
    }
}
