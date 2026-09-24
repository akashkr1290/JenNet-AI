package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PATCH /api/v1/complaints/{id}/classification body (Phase 12, SRS 15.8
 * Features: "manual override by Officer/Department Head with
 * justification"; SRS 16.2 Complaint Detail (Officer View) "Override
 * Classification" - the screen names a mandatory justification field with
 * a 10-character floor, mirrored here as {@code reason}). At least one of
 * {@code category}/{@code severity} must be supplied -
 * ComplaintService.overrideClassification enforces that (a request with
 * both null is not a real override) since Jakarta Bean Validation alone
 * can't express "at least one of".
 */
public record ClassificationOverrideRequest(
        ComplaintCategory category,

        Severity severity,

        @NotBlank
        @Size(min = 10, max = 500)
        String reason
) {
}
