package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.ComplaintRating;

import java.time.LocalDateTime;

public record RatingResponse(
        Long ratingId,
        Long complaintId,
        Integer rating,
        String comment,
        LocalDateTime createdAt
) {
    public static RatingResponse from(ComplaintRating r) {
        return new RatingResponse(
                r.getRatingId(),
                r.getComplaint().getComplaintId(),
                r.getRating(),
                r.getComment(),
                r.getCreatedAt()
        );
    }
}
