package com.jannetai.backend.dto.ward;

import com.jannetai.backend.entity.Ward;

import java.time.LocalDateTime;

/** Audit GAP-020: the Admin view of a ward, including its boundary and inactive rows. */
public record AdminWardResponse(
        Long wardId,
        String name,
        String code,
        String boundaryGeojson,
        boolean hasBoundary,
        Boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AdminWardResponse from(Ward ward) {
        String boundary = ward.getBoundaryGeojson();
        return new AdminWardResponse(ward.getWardId(), ward.getName(), ward.getCode(), boundary,
                boundary != null && !boundary.isBlank(), ward.getIsActive(), ward.getCreatedAt(), ward.getUpdatedAt());
    }
}
