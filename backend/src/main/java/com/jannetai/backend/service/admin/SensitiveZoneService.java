package com.jannetai.backend.service.admin;

import com.jannetai.backend.client.ai.AiLocationSensitivityFlags;
import com.jannetai.backend.dto.zone.SensitiveZoneRequest;
import com.jannetai.backend.dto.zone.SensitiveZoneResponse;
import com.jannetai.backend.entity.Location;
import com.jannetai.backend.entity.SensitiveZone;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.LocationSource;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.SensitiveZoneRepository;
import com.jannetai.backend.service.AuditJson;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.geo.LocationSensitivity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.List;

/**
 * Audit GAP-033 (SRS 15.8): Admin-recorded sensitive places and the flags a
 * complaint location gets from them. See V29__create_sensitive_zones.sql for
 * why these are entered by an Admin rather than taken from a POI source.
 * Approximate locations (WARD_FALLBACK - a ward centroid) never produce flags.
 */
@Service
@RequiredArgsConstructor
public class SensitiveZoneService {

    private final SensitiveZoneRepository sensitiveZoneRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<SensitiveZoneResponse> listAll() {
        return sensitiveZoneRepository.findAllByOrderByNameAsc().stream().map(SensitiveZoneResponse::from).toList();
    }

    @Transactional
    public SensitiveZoneResponse create(User admin, SensitiveZoneRequest request) {
        SensitiveZone zone = sensitiveZoneRepository.save(SensitiveZone.builder()
                .name(request.name().trim())
                .zoneType(request.zoneType())
                .latitude(request.latitude().setScale(6, RoundingMode.HALF_UP))
                .longitude(request.longitude().setScale(6, RoundingMode.HALF_UP))
                .radiusMeters(request.radiusMeters())
                .isActive(true)
                .createdBy(admin)
                .build());
        auditService.record(admin, "SENSITIVE_ZONE_CREATED", "SENSITIVE_ZONE", zone.getZoneId(), AuditJson.of(
                "name", zone.getName(), "zone_type", zone.getZoneType(), "latitude", zone.getLatitude(),
                "longitude", zone.getLongitude(), "radius_meters", zone.getRadiusMeters()));
        return SensitiveZoneResponse.from(zone);
    }

    @Transactional
    public SensitiveZoneResponse setActive(User admin, Long zoneId, boolean active) {
        SensitiveZone zone = sensitiveZoneRepository.findById(zoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Sensitive zone not found: " + zoneId));
        if (!Boolean.valueOf(active).equals(zone.getIsActive())) {
            zone.setIsActive(active);
            zone = sensitiveZoneRepository.save(zone);
            auditService.record(admin, active ? "SENSITIVE_ZONE_ACTIVATED" : "SENSITIVE_ZONE_DEACTIVATED",
                    "SENSITIVE_ZONE", zoneId, AuditJson.of("name", zone.getName()));
        }
        return SensitiveZoneResponse.from(zone);
    }

    /** Flags for the ai-service priority model; NONE for no/approximate location or no zones. */
    @Transactional(readOnly = true)
    public AiLocationSensitivityFlags flagsFor(Location location) {
        if (location == null || location.getLatitude() == null || location.getLongitude() == null
                || location.getSource() == LocationSource.WARD_FALLBACK) {
            return AiLocationSensitivityFlags.NONE_AVAILABLE;
        }
        List<LocationSensitivity.Zone> zones = sensitiveZoneRepository.findByIsActiveTrue().stream()
                .map(z -> new LocationSensitivity.Zone(z.getZoneType().name(), z.getLatitude().doubleValue(),
                        z.getLongitude().doubleValue(), z.getRadiusMeters()))
                .toList();
        if (zones.isEmpty()) {
            return AiLocationSensitivityFlags.NONE_AVAILABLE;
        }
        LocationSensitivity.Flags f = LocationSensitivity.flagsFor(
                location.getLatitude().doubleValue(), location.getLongitude().doubleValue(), zones);
        return new AiLocationSensitivityFlags(f.nearSchool(), f.nearHospital(), f.highTrafficRoad());
    }
}
