package com.jannetai.backend.service.complaint;

import com.jannetai.backend.entity.Location;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.entity.enums.LocationSource;
import com.jannetai.backend.repository.LocationRepository;
import com.jannetai.backend.repository.WardRepository;
import com.jannetai.backend.service.WardService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * GPS Module subset actually needed by Phase 6 (SRS 15.5). What's
 * implemented: municipal-boundary bounds check (Location.outOfJurisdiction
 * flag, not a hard rejection - matches SRS "flagged... for Admin review",
 * not blocked) and optional ward attachment when the client supplies one.
 *
 * What's explicitly NOT implemented (KNOWN LIMITATIONS, PROJECT_PROGRESS.md):
 * real reverse geocoding (no mapping/geocoding provider integrated -
 * ARCHITECTURE.md Section 7 excludes adding new external dependencies
 * without a demonstrated requirement, and none was given this phase),
 * EXIF GPS extraction (would require actually parsing the uploaded
 * image's metadata - deferred), and the location-unavailable ->
 * manual-ward-selection-without-coordinates fallback the SRS's Exceptions
 * text describes - Locations.latitude/longitude are NOT NULL
 * (V5__create_locations.sql), so a coordinate-free submission isn't
 * representable without a schema change; this phase requires
 * latitude/longitude on every submission (device GPS or manual pin, both
 * of which do produce coordinates - the SRS's own Complaint Submission
 * Form, Table 7, marks them mandatory "Yes (auto or manual)") and treats
 * true coordinate-free submission as out of scope, not silently faked.
 */
@Service
@RequiredArgsConstructor
public class LocationService {

    private final LocationRepository locationRepository;
    private final WardService wardService;
    private final WardRepository wardRepository; // audit GAP-008: boundaries for reverse geocoding

    @Value("${app.geo.municipal-min-latitude}")
    private BigDecimal minLatitude;
    @Value("${app.geo.municipal-max-latitude}")
    private BigDecimal maxLatitude;
    @Value("${app.geo.municipal-min-longitude}")
    private BigDecimal minLongitude;
    @Value("${app.geo.municipal-max-longitude}")
    private BigDecimal maxLongitude;

    /**
     * Audit GAP-008 (SRS 15.5 nearest-ward fallback): a GPS point outside every
     * ward boundary is assigned to the nearest ward if it is within this distance.
     */
    @Value("${app.geo.nearest-ward-max-meters:500}")
    private double nearestWardMaxMeters = 500;

    @Transactional
    public Location resolveAndSave(BigDecimal latitude, BigDecimal longitude, Long wardId,
                                    LocationSource source, String formattedAddress) {
        boolean outOfJurisdiction = latitude.compareTo(minLatitude) < 0
                || latitude.compareTo(maxLatitude) > 0
                || longitude.compareTo(minLongitude) < 0
                || longitude.compareTo(maxLongitude) > 0;

        Ward ward = null;
        if (wardId != null) {
            // Client-supplied ward (manual selection) - reuse WardService's
            // existing active-ward validation (Phase 5), same as profile update.
            ward = wardService.requireActiveWardEntity(wardId);
        } else if (!outOfJurisdiction) {
            // Audit GAP-008: GPS submissions used to keep ward_id NULL, which
            // skipped duplicate detection and the ward heatmap for them.
            ward = resolveWard(latitude.doubleValue(), longitude.doubleValue());
        }

        Location location = Location.builder()
                .latitude(latitude)
                .longitude(longitude)
                .ward(ward)
                .formattedAddress(formattedAddress)
                .source(source != null ? source : LocationSource.DEVICE_GPS)
                .outOfJurisdiction(outOfJurisdiction)
                .build();

        return locationRepository.save(location);
    }

    /**
     * Remaining-gaps item 3: GPS unavailable and no coordinates supplied. The
     * ward (chosen by the citizen) is exact; the stored point is an
     * approximation - the ward boundary's centroid when a boundary is on
     * record, otherwise the centre of the configured municipal area
     * (app.geo.*). Source WARD_FALLBACK marks it as approximate everywhere
     * downstream.
     */
    @Transactional
    public Location resolveWardFallbackAndSave(Long wardId) {
        Ward ward = wardService.requireActiveWardEntity(wardId);
        double[] point = WardCentroid.of(ward.getBoundaryGeojson()).orElseGet(() -> new double[]{
                minLatitude.add(maxLatitude).doubleValue() / 2,
                minLongitude.add(maxLongitude).doubleValue() / 2});
        Location location = Location.builder()
                .latitude(BigDecimal.valueOf(point[0]).setScale(6, java.math.RoundingMode.HALF_UP))
                .longitude(BigDecimal.valueOf(point[1]).setScale(6, java.math.RoundingMode.HALF_UP))
                .ward(ward)
                .formattedAddress("Approximate location: " + ward.getName() + " (GPS unavailable)")
                .source(LocationSource.WARD_FALLBACK)
                .outOfJurisdiction(false)
                .build();
        return locationRepository.save(location);
    }

    /**
     * Audit GAP-008: reverse-geocodes a GPS point to an active ward using the
     * wards' boundary GeoJSON (point-in-polygon, then nearest ward within
     * app.geo.nearest-ward-max-meters). Wards without a boundary are skipped;
     * returns null when no ward matches - the complaint is still accepted.
     */
    Ward resolveWard(double latitude, double longitude) {
        List<Ward> wards = wardRepository.findByIsActiveTrueOrderByNameAsc();
        List<WardLocator.WardShape> shapes = WardLocator.shapesOf(
                wards.stream().map(Ward::getWardId).toList(),
                wards.stream().map(Ward::getBoundaryGeojson).toList());
        return WardLocator.locate(shapes, latitude, longitude, nearestWardMaxMeters)
                .flatMap(id -> wards.stream().filter(w -> w.getWardId().equals(id)).findFirst())
                .orElse(null);
    }
}
