package com.jannetai.backend.service.complaint;

import com.jannetai.backend.entity.Location;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.entity.enums.LocationSource;
import com.jannetai.backend.repository.LocationRepository;
import com.jannetai.backend.repository.WardRepository;
import com.jannetai.backend.service.WardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit GAP-008: GPS submissions are reverse-geocoded to a ward (SRS 15.5). */
@ExtendWith(MockitoExtension.class)
class LocationServiceTest {

    // Two adjacent square wards: west [77.50,77.60] and east [77.60,77.70], lat [12.90,13.00]
    private static final String WEST = "{\"type\":\"Polygon\",\"coordinates\":[[[77.50,12.90],[77.60,12.90],[77.60,13.00],[77.50,13.00],[77.50,12.90]]]}";
    private static final String EAST = "{\"type\":\"Polygon\",\"coordinates\":[[[77.60,12.90],[77.70,12.90],[77.70,13.00],[77.60,13.00],[77.60,12.90]]]}";

    @Mock private LocationRepository locationRepository;
    @Mock private WardService wardService;
    @Mock private WardRepository wardRepository;

    private LocationService service;
    private final Ward west = Ward.builder().wardId(1L).name("West").boundaryGeojson(WEST).isActive(true).build();
    private final Ward east = Ward.builder().wardId(2L).name("East").boundaryGeojson(EAST).isActive(true).build();
    private final Ward noBoundary = Ward.builder().wardId(3L).name("Unmapped").isActive(true).build();

    @BeforeEach
    void setUp() {
        service = new LocationService(locationRepository, wardService, wardRepository);
        ReflectionTestUtils.setField(service, "minLatitude", new BigDecimal("6.5"));
        ReflectionTestUtils.setField(service, "maxLatitude", new BigDecimal("37.1"));
        ReflectionTestUtils.setField(service, "minLongitude", new BigDecimal("68.0"));
        ReflectionTestUtils.setField(service, "maxLongitude", new BigDecimal("97.4"));
        ReflectionTestUtils.setField(service, "nearestWardMaxMeters", 500.0);
        lenient().when(locationRepository.save(any(Location.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(wardRepository.findByIsActiveTrueOrderByNameAsc()).thenReturn(List.of(noBoundary, west, east));
    }

    private Location gps(String lat, String lng) {
        return service.resolveAndSave(new BigDecimal(lat), new BigDecimal(lng), null, LocationSource.DEVICE_GPS, null);
    }

    @Test
    void gpsPointInsideAWardBoundaryGetsThatWard() {
        assertThat(gps("12.95", "77.65").getWard()).isSameAs(east);
        assertThat(gps("12.95", "77.55").getWard()).isSameAs(west);
    }

    @Test
    void pointJustOutsideEveryBoundaryFallsBackToTheNearestWard() {
        // ~220 m north of the east ward's top edge (13.00)
        assertThat(gps("13.002", "77.65").getWard()).isSameAs(east);
    }

    @Test
    void pointFarFromEveryWardStaysWardless() {
        assertThat(gps("13.20", "77.65").getWard()).isNull();
    }

    @Test
    void clientChosenWardStillWinsAndSkipsReverseGeocoding() {
        when(wardService.requireActiveWardEntity(1L)).thenReturn(west);

        Location location = service.resolveAndSave(new BigDecimal("12.95"), new BigDecimal("77.65"), 1L,
                LocationSource.MANUAL_PIN, null);

        assertThat(location.getWard()).isSameAs(west);
        verify(wardRepository, never()).findByIsActiveTrueOrderByNameAsc();
    }

    @Test
    void outOfJurisdictionPointIsFlaggedAndNotGeocoded() {
        Location location = gps("40.0", "77.65");

        assertThat(location.getOutOfJurisdiction()).isTrue();
        assertThat(location.getWard()).isNull();
        verify(wardRepository, never()).findByIsActiveTrueOrderByNameAsc();
    }

    // ---- V33: incident location (where the problem is) != submission location ----

    private static final BigDecimal A_LAT = new BigDecimal("12.950000"); // problem, east ward
    private static final BigDecimal A_LNG = new BigDecimal("77.650000");
    private static final BigDecimal B_LAT = new BigDecimal("12.950000"); // citizen's home, west ward (~11 km away)
    private static final BigDecimal B_LNG = new BigDecimal("77.550000");

    private static com.jannetai.backend.dto.complaint.IncidentLocationRequest confirmed(
            BigDecimal lat, BigDecimal lng, LocationSource source, Double accuracy, String capturedAt,
            BigDecimal detectedLat, BigDecimal detectedLng, Double submissionDistance) {
        return new com.jannetai.backend.dto.complaint.IncidentLocationRequest(lat, lng, null, source, true,
                accuracy, capturedAt, detectedLat, detectedLng, submissionDistance);
    }

    private static String daysAgo(int days) {
        return java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHoursMinutes(5, 30)).minusDays(days).toString();
    }

    @Test
    void scenarioA_cameraCaptureGpsConfirmedIsTheIncidentLocation() {
        Location l = service.resolveIncident(confirmed(A_LAT, A_LNG, LocationSource.CAPTURE_GPS, 8.0, daysAgo(0),
                A_LAT, A_LNG, null), null);

        assertThat(l.getLatitude()).isEqualByComparingTo(A_LAT);
        assertThat(l.getLongitude()).isEqualByComparingTo(A_LNG);
        assertThat(l.getSource()).isEqualTo(LocationSource.CAPTURE_GPS);
        assertThat(l.getWard()).isSameAs(east);
        assertThat(l.getConfirmedByCitizen()).isTrue();
        assertThat(l.getAccuracyMeters()).isEqualByComparingTo("8.0");
        assertThat(l.getCapturedAt()).isNotNull();
        assertThat(l.getFlags()).isNull();
    }

    @Test
    void scenarioBandK_photoAtAReportedLaterFromHomeBKeepsAAndStoresOnlyTheDistance() {
        double homeDistance = LocationFlags.distanceMeters(A_LAT.doubleValue(), A_LNG.doubleValue(),
                B_LAT.doubleValue(), B_LNG.doubleValue());

        Location l = service.resolveIncident(confirmed(A_LAT, A_LNG, LocationSource.CAPTURE_GPS, 10.0, daysAgo(2),
                A_LAT, A_LNG, homeDistance), null);

        assertThat(l.getLatitude()).isEqualByComparingTo(A_LAT);
        assertThat(l.getLongitude()).isEqualByComparingTo(A_LNG);
        assertThat(l.getWard()).isSameAs(east); // ward of the PROBLEM, not of the home (west)
        assertThat(l.getSubmissionDistanceMeters().doubleValue()).isGreaterThan(10_000);
        assertThat(l.getFlags()).isNull();
    }

    @Test
    void scenarioC_galleryExifProposalConfirmed() {
        Location l = service.resolveIncident(confirmed(A_LAT, A_LNG, LocationSource.EXIF, null, daysAgo(1),
                A_LAT, A_LNG, null), null);

        assertThat(l.getSource()).isEqualTo(LocationSource.EXIF);
        assertThat(l.getDetectedLatitude()).isEqualByComparingTo(A_LAT);
        assertThat(l.getFlags()).isNull();
    }

    @Test
    void scenarioDEFJ_noPhotoLocationManualPinIsFlaggedNoPhotoLocation() {
        Location l = service.resolveIncident(confirmed(A_LAT, A_LNG, LocationSource.MANUAL_PIN, null, null,
                null, null, null), null);

        assertThat(l.getSource()).isEqualTo(LocationSource.MANUAL_PIN);
        assertThat(l.getWard()).isSameAs(east);
        assertThat(l.getDetectedLatitude()).isNull();
        assertThat(l.getFlags()).isEqualTo(LocationFlags.NO_PHOTO_LOCATION);
    }

    @Test
    void scenarioG_pinMovedFarKeepsTheConfirmedPinAndPreservesTheDetectedPoint() {
        // detected ~330 m south of the confirmed pin
        BigDecimal detectedLat = new BigDecimal("12.947000");
        Location l = service.resolveIncident(confirmed(A_LAT, A_LNG, LocationSource.MANUAL_PIN, 9.0, daysAgo(0),
                detectedLat, A_LNG, null), null);

        assertThat(l.getLatitude()).isEqualByComparingTo(A_LAT);
        assertThat(l.getDetectedLatitude()).isEqualByComparingTo(detectedLat);
        assertThat(l.getDetectedLongitude()).isEqualByComparingTo(A_LNG);
        assertThat(l.getFlags()).isEqualTo(LocationFlags.PIN_MOVED_FAR);
    }

    @Test
    void scenarioG_smallPinCorrectionIsNotFlagged() {
        BigDecimal detectedLat = new BigDecimal("12.949500"); // ~55 m
        Location l = service.resolveIncident(confirmed(A_LAT, A_LNG, LocationSource.MANUAL_PIN, 9.0, daysAgo(0),
                detectedLat, A_LNG, null), null);

        assertThat(l.getFlags()).isNull();
    }

    @Test
    void scenarioHandI_poorAccuracyAndOldPhotoAreFlaggedButAccepted() {
        Location l = service.resolveIncident(confirmed(A_LAT, A_LNG, LocationSource.CAPTURE_GPS, 120.0, daysAgo(9),
                A_LAT, A_LNG, null), null);

        assertThat(l.getLatitude()).isEqualByComparingTo(A_LAT);
        assertThat(LocationFlags.split(l.getFlags()))
                .containsExactly(LocationFlags.STALE_PHOTO, LocationFlags.LOW_ACCURACY);
    }

    @Test
    void confirmedPinIsNeverReplacedByTheServersExif() {
        var exif = new com.jannetai.backend.storage.ExifGps.Coordinates(B_LAT, B_LNG);

        Location l = service.resolveIncident(confirmed(A_LAT, A_LNG, LocationSource.MANUAL_PIN, null, null,
                null, null, null), exif);

        assertThat(l.getLatitude()).isEqualByComparingTo(A_LAT);
        assertThat(l.getLongitude()).isEqualByComparingTo(A_LNG);
        // the server-found photo position is kept for staff and the difference flagged
        assertThat(l.getDetectedLongitude()).isEqualByComparingTo(B_LNG);
        assertThat(l.getFlags()).isEqualTo(LocationFlags.PIN_MOVED_FAR);
    }

    @Test
    void confirmedWardFallbackWithoutCoordinates() {
        when(wardService.requireActiveWardEntity(1L)).thenReturn(west);

        Location l = service.resolveIncident(new com.jannetai.backend.dto.complaint.IncidentLocationRequest(
                null, null, 1L, null, true, null, null, null, null, null), null);

        assertThat(l.getSource()).isEqualTo(LocationSource.WARD_FALLBACK);
        assertThat(l.getWard()).isSameAs(west);
        assertThat(l.getConfirmedByCitizen()).isTrue();
        assertThat(l.getFlags()).isEqualTo(LocationFlags.NO_PHOTO_LOCATION);
    }

    @Test
    void outOfJurisdictionIsFlagged() {
        Location l = service.resolveIncident(confirmed(new BigDecimal("40.0"), A_LNG, LocationSource.MANUAL_PIN,
                null, null, null, null, null), null);

        assertThat(LocationFlags.split(l.getFlags()))
                .containsExactly(LocationFlags.NO_PHOTO_LOCATION, LocationFlags.OUT_OF_JURISDICTION);
    }

    @Test
    void legacyClient_photoExifBeatsDeviceGpsTakenWhenTheScreenOpened() {
        var exif = new com.jannetai.backend.storage.ExifGps.Coordinates(A_LAT, A_LNG);

        Location l = service.resolveIncident(com.jannetai.backend.dto.complaint.IncidentLocationRequest.legacy(
                B_LAT, B_LNG, null, LocationSource.DEVICE_GPS), exif);

        assertThat(l.getLatitude()).isEqualByComparingTo(A_LAT);
        assertThat(l.getLongitude()).isEqualByComparingTo(A_LNG);
        assertThat(l.getSource()).isEqualTo(LocationSource.EXIF);
        assertThat(l.getWard()).isSameAs(east);
        assertThat(l.getConfirmedByCitizen()).isFalse();
        // the device (home) position is reduced to a distance - never stored
        assertThat(l.getSubmissionDistanceMeters().doubleValue()).isGreaterThan(10_000);
    }

    @Test
    void legacyClient_deviceGpsWithoutExifIsKeptAndFlaggedNoPhotoLocation() {
        Location l = service.resolveIncident(com.jannetai.backend.dto.complaint.IncidentLocationRequest.legacy(
                A_LAT, A_LNG, null, LocationSource.DEVICE_GPS), null);

        assertThat(l.getSource()).isEqualTo(LocationSource.DEVICE_GPS);
        assertThat(l.getConfirmedByCitizen()).isFalse();
        assertThat(l.getFlags()).isEqualTo(LocationFlags.NO_PHOTO_LOCATION);
    }

    @Test
    void legacyClient_manualPinIsKeptEvenWithExif() {
        var exif = new com.jannetai.backend.storage.ExifGps.Coordinates(B_LAT, B_LNG);

        Location l = service.resolveIncident(com.jannetai.backend.dto.complaint.IncidentLocationRequest.legacy(
                A_LAT, A_LNG, null, LocationSource.MANUAL_PIN), exif);

        assertThat(l.getLatitude()).isEqualByComparingTo(A_LAT);
        assertThat(l.getSource()).isEqualTo(LocationSource.MANUAL_PIN);
    }

    @Test
    void legacyClient_noCoordinatesUsesExifThenWard() {
        var exif = new com.jannetai.backend.storage.ExifGps.Coordinates(A_LAT, A_LNG);
        Location fromExif = service.resolveIncident(com.jannetai.backend.dto.complaint.IncidentLocationRequest.legacy(
                null, null, null, null), exif);
        assertThat(fromExif.getSource()).isEqualTo(LocationSource.EXIF);

        when(wardService.requireActiveWardEntity(1L)).thenReturn(west);
        Location fromWard = service.resolveIncident(com.jannetai.backend.dto.complaint.IncidentLocationRequest.legacy(
                null, null, 1L, null), null);
        assertThat(fromWard.getSource()).isEqualTo(LocationSource.WARD_FALLBACK);
    }

    @Test
    void invalidInputIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resolveIncident(
                        confirmed(A_LAT, null, LocationSource.MANUAL_PIN, null, null, null, null, null), null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resolveIncident(
                        confirmed(A_LAT, A_LNG, LocationSource.WARD_FALLBACK, null, null, null, null, null), null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resolveIncident(
                        confirmed(A_LAT, A_LNG, LocationSource.CAPTURE_GPS, -1.0, null, null, null, null), null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resolveIncident(
                        confirmed(A_LAT, A_LNG, LocationSource.CAPTURE_GPS, null, "yesterday", null, null, null), null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resolveIncident(
                        confirmed(A_LAT, A_LNG, LocationSource.CAPTURE_GPS, null, null, A_LAT, null, null), null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resolveIncident(
                        new com.jannetai.backend.dto.complaint.IncidentLocationRequest(
                                null, null, null, null, true, null, null, null, null, null), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPhotoTimeInTheFutureIsIgnoredAndOffsetsAreStoredAsUtc() {
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 10, 2, 4, 0);
        assertThat(LocationService.parseCapturedAt("2026-10-02T09:15:00+05:30", now))
                .isEqualTo(java.time.LocalDateTime.of(2026, 10, 2, 3, 45));
        assertThat(LocationService.parseCapturedAt("2026-10-03T09:15:00Z", now)).isNull();
        assertThat(LocationService.parseCapturedAt(null, now)).isNull();
    }
}
