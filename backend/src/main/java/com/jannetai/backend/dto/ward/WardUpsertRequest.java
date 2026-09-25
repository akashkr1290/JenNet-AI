package com.jannetai.backend.dto.ward;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Audit GAP-020 (SRS 15.11): create or replace a ward. {@code boundaryGeojson}
 * is a GeoJSON Polygon/MultiPolygon (or a Feature holding one) with
 * [longitude, latitude] positions; null or blank means "no boundary on record"
 * (the ward then cannot be found by GPS - WardLocator skips it). An update
 * replaces all three fields.
 */
public record WardUpsertRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 30) String code,
        @Size(max = 2_000_000) String boundaryGeojson
) {
}
