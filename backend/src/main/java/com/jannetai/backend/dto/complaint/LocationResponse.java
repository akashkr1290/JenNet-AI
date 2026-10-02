package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.Location;
import com.jannetai.backend.entity.enums.LocationSource;
import com.jannetai.backend.service.complaint.LocationFlags;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The complaint's INCIDENT location (where the problem is). V33 added the
 * location-check fields shown to staff: detected point, accuracy, photo time,
 * citizen confirmation, review flags and the optional submission distance
 * (never the citizen's own coordinates). All new fields are null/false/empty
 * for complaints created before V33.
 */
public record LocationResponse(
        Long locationId,
        BigDecimal latitude,
        BigDecimal longitude,
        Long wardId,
        String wardName,
        String formattedAddress,
        LocationSource source,
        Boolean outOfJurisdiction,
        BigDecimal accuracyMeters,
        LocalDateTime capturedAt,
        BigDecimal detectedLatitude,
        BigDecimal detectedLongitude,
        Boolean confirmedByCitizen,
        List<String> flags,
        BigDecimal submissionDistanceMeters
) {
    public static LocationResponse from(Location location) {
        return new LocationResponse(
                location.getLocationId(),
                location.getLatitude(),
                location.getLongitude(),
                location.getWard() != null ? location.getWard().getWardId() : null,
                location.getWard() != null ? location.getWard().getName() : null,
                location.getFormattedAddress(),
                location.getSource(),
                location.getOutOfJurisdiction(),
                location.getAccuracyMeters(),
                location.getCapturedAt(),
                location.getDetectedLatitude(),
                location.getDetectedLongitude(),
                Boolean.TRUE.equals(location.getConfirmedByCitizen()),
                LocationFlags.split(location.getFlags()),
                location.getSubmissionDistanceMeters()
        );
    }
}
