package com.jannetai.backend.controller;

import com.jannetai.backend.service.complaint.AiFeedbackExportService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Gap-backlog Patch 26/34 (Sep 2026 audit): "AI Feedback Loop" dataset
 * export - see {@link AiFeedbackExportService}'s Javadoc for exactly
 * what this does and does not cover. ADMIN/SUPER_ADMIN only, same
 * reasoning as {@code AdminAuditLogController}: a bulk export of every
 * complaint's AI-vs-human classification is an administrative/ML-
 * pipeline concern, not something any staff role needs.
 */
@RestController
@RequestMapping("/api/v1/admin/ai-feedback")
@RequiredArgsConstructor
@Tag(name = "AI Feedback Export", description = "AI-vs-human classification dataset export for retraining (Gap-backlog Patch 26)")
public class AiFeedbackExportController {

    private final AiFeedbackExportService aiFeedbackExportService;

    /** Gap-backlog Patch 52: human-override rate overall and per AI class (drift signal). */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public java.util.Map<String, Object> summary() {
        return aiFeedbackExportService.overrideSummary();
    }

    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> export() {
        String csv = aiFeedbackExportService.exportCsv();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ai-feedback-dataset.csv\"")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
