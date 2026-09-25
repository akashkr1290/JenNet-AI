package com.jannetai.backend.client.ai;

/**
 * Mirrors ai-service's {@code app/schemas/priority_predict.py:
 * LocationSensitivityFlags} field-for-field (Phase 10 contract).
 *
 * Audit GAP-033: filled from the Admin-recorded sensitive zones
 * (sensitive_zones, V29; SensitiveZoneService#flagsFor) - a complaint inside
 * an active school / hospital / high-traffic-road circle sends the matching
 * flag. There is still no external POI source (e.g. OpenStreetMap); without
 * recorded zones every flag stays false ({@link #NONE_AVAILABLE}).
 */
public record AiLocationSensitivityFlags(
        boolean nearSchool,
        boolean nearHospital,
        boolean highTrafficRoad
) {
    public static final AiLocationSensitivityFlags NONE_AVAILABLE =
            new AiLocationSensitivityFlags(false, false, false);
}
