package com.jannetai.backend.service.complaint;

import com.jannetai.backend.dto.complaint.IncidentLocationRequest;
import com.jannetai.backend.entity.Location;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.entity.enums.LocationSource;
import com.jannetai.backend.repository.LocationRepository;
import com.jannetai.backend.repository.WardRepository;
import com.jannetai.backend.service.WardService;
import com.jannetai.backend.storage.ExifGps;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * GPS Module subset actually needed by Phase 6 (SRS 15.5). What's
 * implemented: municipal-boundary bounds check (Location.outOfJurisdiction
 * flag, not a hard rejection - matches SRS "flagged... for Admin review",
 * not blocked) and optional ward attachment when the client supplies one.
 *
 * Later additions: ward-centroid fallback without coordinates (remaining-gaps
 * item 3, WARD_FALLBACK), polygon/nearest-ward reverse geocoding from the
 * wards' boundary GeoJSON (audit GAP-008), and EXIF GPS as the fallback when
 * the device sent no coordinates (audit GAP-031; read by ComplaintService via
 * ImageValidationService#readExifGps before the metadata strip, source EXIF).
 *
 * V33 (incident location): {@link #resolveIncident} is the entry point for
 * new complaints. The stored point is the INCIDENT location - where the
 * problem is - never automatically the citizen's position at submission. The
 * Flutter app proposes capture-time GPS or the photo's EXIF GPS, the citizen
 * confirms or corrects it on an OpenStreetMap map (or places a pin, or picks a
 * ward), and that confirmed point drives ward, jurisdiction, duplicates and
 * heatmaps exactly as before. The detected point, accuracy, photo time and
 * review flags ({@link LocationFlags}) are kept for staff.
 *
 * Still NOT implemented: street-address reverse geocoding (no geocoding
 * provider is integrated). The jurisdiction box
 * (app.geo.municipal-*) must be set to the real municipal boundary by the
 * operator; the default is a placeholder.
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

    /** V33 review-flag thresholds (app.location.*) - warnings only. */
    @Value("${app.location.stale-photo-days:7}")
    private int stalePhotoDays = 7;
    @Value("${app.location.low-accuracy-meters:50}")
    private double lowAccuracyMeters = 50;
    @Value("${app.location.pin-moved-far-meters:200}")
    private double pinMovedFarMeters = 200;

    /** A photo time this far in the future is treated as a wrong device clock and ignored. */
    private static final long CAPTURED_AT_FUTURE_TOLERANCE_MINUTES = 10;
    private static final double MAX_ACCURACY_METERS = 100_000;
    private static final double MAX_DISTANCE_METERS = 21_000_000; // > half Earth's circumference

    /**
     * V33: resolves and saves the INCIDENT location of a new complaint.
     *
     * <p>Priority (product decision 2026-10-02, SRS 15.5 device GPS / EXIF
     * fallback / map pin / ward fallback):
     * <ol>
     *   <li>Citizen-confirmed point ({@code confirmed=true}, new clients): it is
     *       authoritative - whatever the source (CAPTURE_GPS, EXIF, MANUAL_PIN).
     *       The server never replaces it. With no coordinates and a ward, it is
     *       a ward fallback the citizen chose.</li>
     *   <li>Older clients (not confirmed): the photo's own EXIF GPS now wins over
     *       DEVICE_GPS, because DEVICE_GPS was read when the screen opened and
     *       may be the citizen's home (the bug this release fixes). The device
     *       position is reduced to a distance and discarded. An explicit
     *       MANUAL_PIN is kept. No coordinates: EXIF, else the ward fallback.</li>
     * </ol>
     *
     * @param exif GPS read by the server from the ORIGINAL upload (before the
     *             metadata strip), or null
     */
    @Transactional
    public Location resolveIncident(IncidentLocationRequest request, ExifGps.Coordinates exif) {
        BigDecimal latitude = request.latitude();
        BigDecimal longitude = request.longitude();
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("latitude and longitude must be provided together");
        }
        if ((request.detectedLatitude() == null) != (request.detectedLongitude() == null)) {
            throw new IllegalArgumentException("detectedLatitude and detectedLongitude must be provided together");
        }
        if (latitude != null) {
            requireCoordinate(latitude, 90, "latitude");
            requireCoordinate(longitude, 180, "longitude");
            if (request.source() == LocationSource.WARD_FALLBACK) {
                throw new IllegalArgumentException("WARD_FALLBACK is assigned by the server and cannot be sent with coordinates");
            }
        }
        if (request.detectedLatitude() != null) {
            requireCoordinate(request.detectedLatitude(), 90, "detectedLatitude");
            requireCoordinate(request.detectedLongitude(), 180, "detectedLongitude");
        }
        BigDecimal accuracy = nonNegative(request.accuracyMeters(), MAX_ACCURACY_METERS, "locationAccuracyMeters");
        BigDecimal submissionDistance = nonNegative(request.submissionDistanceMeters(), MAX_DISTANCE_METERS,
                "submissionDistanceMeters");
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime capturedAt = parseCapturedAt(request.capturedAt(), nowUtc);

        BigDecimal detectedLat = request.detectedLatitude();
        BigDecimal detectedLng = request.detectedLongitude();
        LocationSource source = request.source();
        Long wardId = request.wardId();
        boolean wardFallback = false;

        if (request.isConfirmed()) {
            if (latitude == null) {
                if (wardId == null) {
                    throw new IllegalArgumentException(
                            "A location is required: confirm the problem location on the map, or choose the ward");
                }
                wardFallback = true;
            } else if (source == null) {
                source = LocationSource.MANUAL_PIN;
            }
            if (detectedLat == null && exif != null) {
                // The client found no location in the photo but the server did:
                // keep it for staff comparison (never replaces the confirmed pin).
                detectedLat = exif.latitude();
                detectedLng = exif.longitude();
            }
        } else if (latitude == null) {
            if (exif != null) {
                latitude = exif.latitude();
                longitude = exif.longitude();
                source = LocationSource.EXIF;
                detectedLat = exif.latitude();
                detectedLng = exif.longitude();
            } else if (wardId == null) {
                throw new IllegalArgumentException(
                        "A location is required: send latitude and longitude, or choose your ward if GPS is unavailable "
                                + "(the photo has no GPS position either)");
            } else {
                wardFallback = true;
            }
        } else if (exif != null && (source == null || source == LocationSource.DEVICE_GPS)) {
            // Older client: GPS from when the screen opened. The photo knows
            // where it was taken - that is the incident location.
            if (submissionDistance == null) {
                submissionDistance = distance(latitude, longitude, exif.latitude(), exif.longitude());
            }
            latitude = exif.latitude();
            longitude = exif.longitude();
            source = LocationSource.EXIF;
            detectedLat = exif.latitude();
            detectedLng = exif.longitude();
            accuracy = null; // the device's accuracy described the discarded position
            wardId = null;   // re-derive the ward from the photo's position
        } else if (source == null) {
            source = LocationSource.DEVICE_GPS;
        }
        if (source == LocationSource.CAPTURE_GPS || source == LocationSource.EXIF) {
            if (detectedLat == null) {
                detectedLat = latitude;
                detectedLng = longitude;
            }
        }

        Location location = wardFallback
                ? buildWardFallback(wardId)
                : build(latitude, longitude, wardId, source, null);
        location.setDetectedLatitude(scale6(detectedLat));
        location.setDetectedLongitude(scale6(detectedLng));
        location.setAccuracyMeters(accuracy);
        location.setCapturedAt(capturedAt);
        location.setConfirmedByCitizen(request.isConfirmed());
        location.setSubmissionDistanceMeters(submissionDistance);
        location.setFlags(LocationFlags.join(LocationFlags.compute(
                location.getLatitude(), location.getLongitude(), location.getDetectedLatitude(),
                location.getDetectedLongitude(), accuracy, capturedAt,
                Boolean.TRUE.equals(location.getOutOfJurisdiction()), nowUtc,
                new LocationFlags.Thresholds(stalePhotoDays, lowAccuracyMeters, pinMovedFarMeters))));
        return locationRepository.save(location);
    }

    private static void requireCoordinate(BigDecimal value, int bound, String field) {
        if (value.abs().compareTo(BigDecimal.valueOf(bound)) > 0) {
            throw new IllegalArgumentException(field + " must be between -" + bound + " and " + bound);
        }
    }

    private static BigDecimal nonNegative(Double value, double max, String field) {
        if (value == null) {
            return null;
        }
        if (value.isNaN() || value < 0 || value > max) {
            throw new IllegalArgumentException(field + " must be between 0 and " + (long) max);
        }
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    /** ISO-8601 with an offset; stored as UTC. A time in the future (wrong clock) is ignored. */
    static LocalDateTime parseCapturedAt(String value, LocalDateTime nowUtc) {
        if (value == null || value.isBlank()) {
            return null;
        }
        LocalDateTime utc;
        try {
            utc = OffsetDateTime.parse(value.trim()).atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "photoCapturedAt must be an ISO-8601 date-time with offset, e.g. 2026-10-02T09:15:00+05:30");
        }
        return utc.isAfter(nowUtc.plusMinutes(CAPTURED_AT_FUTURE_TOLERANCE_MINUTES)) ? null : utc;
    }

    private static BigDecimal distance(BigDecimal lat1, BigDecimal lng1, BigDecimal lat2, BigDecimal lng2) {
        return BigDecimal.valueOf(LocationFlags.distanceMeters(lat1.doubleValue(), lng1.doubleValue(),
                lat2.doubleValue(), lng2.doubleValue())).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale6(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
    }

    @Transactional
    public Location resolveAndSave(BigDecimal latitude, BigDecimal longitude, Long wardId,
                                    LocationSource source, String formattedAddress) {
        return locationRepository.save(build(latitude, longitude, wardId, source, formattedAddress));
    }

    private Location build(BigDecimal latitude, BigDecimal longitude, Long wardId,
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

        return location;
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
        return locationRepository.save(buildWardFallback(wardId));
    }

    private Location buildWardFallback(Long wardId) {
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
        return location;
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
