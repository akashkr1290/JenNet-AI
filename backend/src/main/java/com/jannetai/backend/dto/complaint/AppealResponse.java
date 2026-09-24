package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.ComplaintAppeal;
import com.jannetai.backend.entity.enums.AppealStatus;

import java.time.LocalDateTime;

public record AppealResponse(
        Long appealId,
        Long complaintId,
        String reason,
        AppealStatus status,
        String reviewedByName,
        LocalDateTime reviewedAt,
        String reviewNote,
        LocalDateTime createdAt
) {
    public static AppealResponse from(ComplaintAppeal a) {
        return new AppealResponse(
                a.getAppealId(),
                a.getComplaint().getComplaintId(),
                a.getReason(),
                a.getStatus(),
                a.getReviewedBy() != null ? a.getReviewedBy().getFullName() : null,
                a.getReviewedAt(),
                a.getReviewNote(),
                a.getCreatedAt()
        );
    }
}
