package com.jannetai.backend.entity.enums;

/** Matches locations.source CHECK constraint (V5__create_locations.sql). */
public enum LocationSource {
    DEVICE_GPS,
    EXIF,
    MANUAL_PIN,
    /**
     * Remaining-gaps item 3: GPS unavailable and no coordinates entered - the
     * citizen chose their ward, and the stored point is an APPROXIMATION
     * (ward boundary centroid, else municipal-area centre). Always set by the
     * server, never accepted from a client. Duplicate detection sends no
     * coordinates for such locations, so ai-service applies its SRS 15.6
     * "GPS unavailable" rule (raised similarity threshold, no proximity).
     */
    WARD_FALLBACK
}
