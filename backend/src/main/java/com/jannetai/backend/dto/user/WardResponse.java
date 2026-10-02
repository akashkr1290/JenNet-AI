package com.jannetai.backend.dto.user;

import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.service.complaint.WardCentroid;

/**
 * Public-safe ward shape for GET /api/v1/wards (Phase 5, Citizen Module).
 * Deliberately excludes boundaryGeojson - there's no confirmed client use
 * case for shipping a raw GeoJSON blob to Flutter.
 *
 * V33: {@code centreLatitude}/{@code centreLongitude} - the centre of the
 * ward's boundary (null when no boundary is on record), used by the
 * Flutter incident-location map to jump to a ward when the citizen searches
 * for an area. A single public reference point, not the boundary.
 */
public record WardResponse(
        Long wardId,
        String name,
        String code,
        Double centreLatitude,
        Double centreLongitude
) {
    public WardResponse(Long wardId, String name, String code) {
        this(wardId, name, code, null, null);
    }

    public static WardResponse from(Ward ward) {
        double[] centre = WardCentroid.of(ward.getBoundaryGeojson()).orElse(null);
        return new WardResponse(ward.getWardId(), ward.getName(), ward.getCode(),
                centre != null ? centre[0] : null, centre != null ? centre[1] : null);
    }
}
