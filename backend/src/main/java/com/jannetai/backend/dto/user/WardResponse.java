package com.jannetai.backend.dto.user;

import com.jannetai.backend.entity.Ward;

/**
 * Public-safe ward shape for GET /api/v1/wards (Phase 5, Citizen Module).
 * Deliberately excludes boundaryGeojson - that field is not yet consumed by
 * anything (V1__create_wards.sql header comment) and there's no confirmed
 * client use case for shipping a raw GeoJSON blob to Flutter this phase.
 */
public record WardResponse(
        Long wardId,
        String name,
        String code
) {
    public static WardResponse from(Ward ward) {
        return new WardResponse(ward.getWardId(), ward.getName(), ward.getCode());
    }
}
