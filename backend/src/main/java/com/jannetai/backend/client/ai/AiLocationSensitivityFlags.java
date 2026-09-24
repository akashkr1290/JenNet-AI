package com.jannetai.backend.client.ai;

/**
 * Mirrors ai-service's {@code app/schemas/priority_predict.py:
 * LocationSensitivityFlags} field-for-field (Phase 10 contract).
 *
 * KNOWN LIMITATION (documented, not fixed this phase): every field is
 * always {@code false} from this backend today. SRS 15.8 Features asks
 * for "proximity to schools, hospitals, high-traffic roads" weighting,
 * but no POI/geometry data source exists anywhere in this project's
 * schema - {@code Ward.boundaryGeojson} (V1) has been unused since it was
 * added, and no schools/hospitals/traffic layer was ever modelled. Rather
 * than fabricate these flags, {@link #NONE_AVAILABLE} is sent honestly -
 * ai-service's own {@code priority_service.py} implements the real
 * weighting logic for when a future phase wires a real data source in
 * (same "build the real logic, mark the input unavailable" pattern used
 * for YOLOv11's untrained weights).
 */
public record AiLocationSensitivityFlags(
        boolean nearSchool,
        boolean nearHospital,
        boolean highTrafficRoad
) {
    public static final AiLocationSensitivityFlags NONE_AVAILABLE =
            new AiLocationSensitivityFlags(false, false, false);
}
