package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.enums.AppealStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code decision} must be APPROVED or DENIED - PENDING is rejected by ComplaintAppealService (a review always resolves the appeal one way or the other). */
public record AppealReviewRequest(
        @NotNull AppealStatus decision,
        @Size(max = 1000) String note
) {
}
