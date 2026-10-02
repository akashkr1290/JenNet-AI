package com.jannetai.backend.entity.enums;

/**
 * Matches locations.source CHECK constraint (V5, widened by V23 and V33).
 * How the INCIDENT location (where the problem is) was obtained. Priority on
 * new clients: CAPTURE_GPS, then EXIF, then MANUAL_PIN, then WARD_FALLBACK;
 * the citizen confirms the point on a map in every case.
 */
public enum LocationSource {
    /**
     * Older app versions only: the phone's position when the complaint screen
     * was opened (which may be the citizen's home, not the problem). New
     * clients never send it - see V33__incident_location_capture.sql.
     */
    DEVICE_GPS,
    /** V33: phone GPS read right after the photo was taken with the in-app camera. */
    CAPTURE_GPS,
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
