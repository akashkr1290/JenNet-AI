package com.jannetai.backend.dto.complaint;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/complaints/{id}/notes body (Phase 12, SRS 16.2 Complaint
 * Detail (Officer View) "Add Internal Note"). Length cap matches SRS
 * Table 8's {@code officer_note} field (varchar(1000)) - the SRS doesn't
 * define a separate internal-note field width, and this is functionally
 * the same free-text staff annotation concept.
 */
public record InternalNoteRequest(
        @NotBlank
        @Size(max = 1000)
        String note
) {
}
