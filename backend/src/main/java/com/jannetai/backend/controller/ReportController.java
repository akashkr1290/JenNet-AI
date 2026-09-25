package com.jannetai.backend.controller;

import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.dto.report.PeriodReport;
import com.jannetai.backend.service.report.PeriodReportCodec;
import com.jannetai.backend.service.report.PeriodReportService;
import com.jannetai.backend.service.report.ReportPdfService;
import com.jannetai.backend.service.report.ReportPeriods;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Gap-backlog Patch 18: PDF overview report, same roles and scoping as GET /api/v1/dashboard/overview. */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(name = "Reports", description = "PDF report export (Gap-backlog Patch 18)")
public class ReportController {

    private final ReportPdfService reportPdfService;
    private final PeriodReportService periodReportService; // audit GAP-039

    @GetMapping(value = "/overview.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyRole('DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> overviewPdf(@AuthenticationPrincipal UserPrincipal principal,
                                              @RequestParam(required = false) Long departmentId) {
        byte[] pdf = reportPdfService.overviewPdf(principal.getUser(), departmentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"jannet-overview-report.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    // ---- Audit GAP-039 (SRS 15.12 / 21 / US-10): period reports with an explicit date range ----
    // type = DAILY | WEEKLY | CUSTOM; dates are ISO yyyy-MM-dd in the reporting zone (app.reports.zone).
    // Every generated report is stored as an immutable snapshot (V28); see PeriodReportService.

    @GetMapping("/period")
    @PreAuthorize("hasAnyRole('DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public PeriodReport period(@AuthenticationPrincipal UserPrincipal principal,
                               @RequestParam(defaultValue = "CUSTOM") ReportPeriods.Type type,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) Long departmentId) {
        return periodReportService.generate(principal.getUser(), type, from, to, departmentId);
    }

    @GetMapping(value = "/period.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyRole('DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> periodPdf(@AuthenticationPrincipal UserPrincipal principal,
                                            @RequestParam(defaultValue = "CUSTOM") ReportPeriods.Type type,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(required = false) Long departmentId) {
        return pdf(periodReportService.generate(principal.getUser(), type, from, to, departmentId));
    }

    @GetMapping(value = "/period.csv", produces = "text/csv")
    @PreAuthorize("hasAnyRole('DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> periodCsv(@AuthenticationPrincipal UserPrincipal principal,
                                            @RequestParam(defaultValue = "CUSTOM") ReportPeriods.Type type,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(required = false) Long departmentId) {
        return csv(periodReportService.generate(principal.getUser(), type, from, to, departmentId));
    }

    @GetMapping("/snapshots")
    @PreAuthorize("hasAnyRole('DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public Page<PeriodReport> snapshots(@AuthenticationPrincipal UserPrincipal principal,
                                        @RequestParam(required = false) Long departmentId,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int pageSize) {
        return periodReportService.listSnapshots(principal.getUser(), departmentId, page, pageSize);
    }

    @GetMapping("/snapshots/{id}")
    @PreAuthorize("hasAnyRole('DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public PeriodReport snapshot(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return periodReportService.getSnapshot(principal.getUser(), id);
    }

    @GetMapping(value = "/snapshots/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyRole('DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> snapshotPdf(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return pdf(periodReportService.getSnapshot(principal.getUser(), id));
    }

    @GetMapping(value = "/snapshots/{id}/csv", produces = "text/csv")
    @PreAuthorize("hasAnyRole('DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> snapshotCsv(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return csv(periodReportService.getSnapshot(principal.getUser(), id));
    }

    private ResponseEntity<byte[]> pdf(PeriodReport report) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName(report) + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(reportPdfService.periodPdf(report));
    }

    private static ResponseEntity<byte[]> csv(PeriodReport report) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName(report) + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(PeriodReportCodec.toCsv(report).getBytes(StandardCharsets.UTF_8));
    }

    private static String fileName(PeriodReport r) {
        return "jannet-" + r.reportType().toLowerCase(java.util.Locale.ROOT) + "-report-" + r.periodStart() + "_" + r.periodEnd();
    }
}
