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
 * Scope honestly limited to this one report: the patch's daily report,
 * budget report and citizen-engagement report are NOT implemented.
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
