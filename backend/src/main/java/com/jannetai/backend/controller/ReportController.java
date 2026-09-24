package com.jannetai.backend.controller;

import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.report.ReportPdfService;
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
}
