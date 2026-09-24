package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.Severity;
import com.jannetai.backend.entity.enums.VerificationDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * PATCH /api/v1/complaints/{id}/verify body - the manual Verification Team
 * override approved for the pre-AI-service gap (Phase 6 instruction; SRS
 * 13.6). Field requirements depend on {@code decision}
 * (ComplaintService.verify enforces these, since jakarta.validation alone
 * can't express "required if X"):
 * <ul>
 *   <li>VERIFIED: {@code category} required (stands in for the AI
 *       classification result that doesn't exist yet); {@code severity}
 *       optional (Priority Prediction Module, not built until Phase 10 -
 *       left null if omitted).</li>
 *   <li>REJECTED: {@code rejectionReasonCode} required (SRS 15.3:
 *       "mandatory reason code").</li>
 *   <li>DUPLICATE: {@code parentComplaintId} required (SRS 15.6: merge
 *       target, increments the parent's corroboration_count).</li>
 * </ul>
 */
public record VerificationDecisionRequest(
        @NotNull
        VerificationDecision decision,

        ComplaintCategory category,

        Severity severity,

        @Size(max = 50)
        String rejectionReasonCode,

        Long parentComplaintId,

        @Size(max = 500)
        String note
) {
}
