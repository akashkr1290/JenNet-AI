package com.jannetai.backend.dto.zone;

import com.jannetai.backend.entity.SensitiveZone;
import com.jannetai.backend.entity.enums.SensitiveZoneType;

import java.math.BigDecimal;

/** Audit GAP-033. */
public record SensitiveZoneResponse(Long zoneId, String name, SensitiveZoneType zoneType, BigDecimal latitude,
                                    BigDecimal longitude, Integer radiusMeters, Boolean isActive) {
    public static SensitiveZoneResponse from(SensitiveZone z) {
        return new SensitiveZoneResponse(z.getZoneId(), z.getName(), z.getZoneType(), z.getLatitude(),
                z.getLongitude(), z.getRadiusMeters(), z.getIsActive());
    }
}
