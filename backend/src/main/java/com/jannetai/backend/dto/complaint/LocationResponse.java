package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.Location;
import com.jannetai.backend.entity.enums.LocationSource;

import java.math.BigDecimal;

public record LocationResponse(
        Long locationId,
        BigDecimal latitude,
        BigDecimal longitude,
        Long wardId,
        String wardName,
        String formattedAddress,
        LocationSource source,
        Boolean outOfJurisdiction
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
                location.getOutOfJurisdiction()
        );
    }
}
