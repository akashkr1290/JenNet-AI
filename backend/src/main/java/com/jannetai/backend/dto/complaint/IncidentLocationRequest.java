package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.enums.LocationSource;

import java.math.BigDecimal;

/**
 * V33: the incident-location part of POST /api/v1/complaints (multipart
 * fields). {@code latitude}/{@code longitude} are the INCIDENT point - where
 * the problem is - never the citizen's position at submission time.
 *
 * @param latitude                 incident point (the confirmed pin), or null with a ward fallback
 * @param longitude                incident point
 * @param wardId                   ward chosen when the map cannot be used (ward fallback);
 *                                 older clients may also send it with coordinates
 * @param source                   CAPTURE_GPS | EXIF | MANUAL_PIN (DEVICE_GPS: older clients)
 * @param confirmed                the citizen confirmed the point on the map (new clients)
 * @param accuracyMeters           device accuracy of the detected GPS fix
 * @param capturedAt               when the photo was taken, ISO-8601 with offset
 *                                 (e.g. 2026-10-02T09:15:00+05:30 or ...Z)
 * @param detectedLatitude         the automatically detected point (capture GPS / EXIF),
 *                                 kept when the citizen moves the pin
 * @param detectedLongitude        see detectedLatitude
 * @param submissionDistanceMeters optional distance between the incident point and the
 *                                 citizen's position when submitting (the position itself
 *                                 is never sent or stored)
 */
public record IncidentLocationRequest(
        BigDecimal latitude,
        BigDecimal longitude,
        Long wardId,
        LocationSource source,
        Boolean confirmed,
        Double accuracyMeters,
        String capturedAt,
        BigDecimal detectedLatitude,
        BigDecimal detectedLongitude,
        Double submissionDistanceMeters
) {
    /** What clients before V33 sent: coordinates (GPS at screen open), ward, source. */
    public static IncidentLocationRequest legacy(BigDecimal latitude, BigDecimal longitude, Long wardId,
                                                 LocationSource source) {
        return new IncidentLocationRequest(latitude, longitude, wardId, source, null, null, null, null, null, null);
    }

    public boolean isConfirmed() {
        return Boolean.TRUE.equals(confirmed);
    }
}
