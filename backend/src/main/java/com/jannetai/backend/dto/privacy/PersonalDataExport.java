package com.jannetai.backend.dto.privacy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Audit GAP-041 (SRS 24 Compliance: citizen data ACCESS request): everything
 * the platform stores about the requesting user as a person. Internal staff
 * notes, other users' data and audit logs are not part of it.
 */
public record PersonalDataExport(
        LocalDateTime exportedAt,
        Profile profile,
        Map<String, String> settings,
        List<ComplaintItem> complaints,
        List<AppealItem> appeals,
        List<RatingItem> ratings
) {
    public record Profile(Long userId, String fullName, String mobileNumber, String email, String role,
                          String status, Long wardId, String wardName, Integer reputationScore,
                          LocalDateTime mobileVerifiedAt, LocalDateTime emailVerifiedAt, LocalDateTime createdAt) {
    }

    public record ComplaintItem(Long complaintId, String referenceNumber, String category, String status,
                                String description, BigDecimal latitude, BigDecimal longitude,
                                String address, LocalDateTime createdAt) {
    }

    public record AppealItem(Long appealId, Long complaintId, String reason, String status, LocalDateTime createdAt) {
    }

    public record RatingItem(Long complaintId, Integer rating, String comment, LocalDateTime createdAt) {
    }
}
