package com.jannetai.backend.service.complaint;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** V33 incident-location review flags - warnings only. */
class LocationFlagsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 2, 12, 0);
    private static final BigDecimal LAT = new BigDecimal("28.613900");
    private static final BigDecimal LNG = new BigDecimal("77.209000");

    @Test
    void cleanCaptureHasNoFlags() {
        assertThat(LocationFlags.compute(LAT, LNG, LAT, LNG, new BigDecimal("10"), NOW.minusHours(1), false, NOW,
                LocationFlags.Thresholds.DEFAULTS)).isEmpty();
    }

    @Test
    void thresholdsAreExclusiveAndConfigurable() {
        // exactly 7 days old / 50 m accuracy -> not flagged
        assertThat(LocationFlags.compute(LAT, LNG, LAT, LNG, new BigDecimal("50"), NOW.minusDays(7), false, NOW,
                LocationFlags.Thresholds.DEFAULTS)).isEmpty();
        assertThat(LocationFlags.compute(LAT, LNG, LAT, LNG, new BigDecimal("50.1"), NOW.minusDays(7).minusMinutes(1),
                false, NOW, LocationFlags.Thresholds.DEFAULTS))
                .containsExactly(LocationFlags.STALE_PHOTO, LocationFlags.LOW_ACCURACY);
        assertThat(LocationFlags.compute(LAT, LNG, LAT, LNG, new BigDecimal("50.1"), NOW.minusDays(7).minusMinutes(1),
                false, NOW, new LocationFlags.Thresholds(30, 100, 200))).isEmpty();
    }

    @Test
    void pinMovedFarComparesDetectedAndConfirmedPoints() {
        BigDecimal movedLat = new BigDecimal("28.616000"); // ~234 m north
        assertThat(LocationFlags.compute(movedLat, LNG, LAT, LNG, null, null, false, NOW,
                LocationFlags.Thresholds.DEFAULTS)).containsExactly(LocationFlags.PIN_MOVED_FAR);
        assertThat(LocationFlags.compute(movedLat, LNG, LAT, LNG, null, null, false, NOW,
                new LocationFlags.Thresholds(7, 50, 300))).isEmpty();
    }

    @Test
    void noDetectedPointAndOutOfJurisdiction() {
        assertThat(LocationFlags.compute(LAT, LNG, null, null, null, null, true, NOW,
                LocationFlags.Thresholds.DEFAULTS))
                .containsExactly(LocationFlags.NO_PHOTO_LOCATION, LocationFlags.OUT_OF_JURISDICTION);
    }

    @Test
    void storedFormRoundTrips() {
        assertThat(LocationFlags.join(java.util.List.of())).isNull();
        assertThat(LocationFlags.join(java.util.List.of("A", "B"))).isEqualTo("A,B");
        assertThat(LocationFlags.split("A, B,")).containsExactly("A", "B");
        assertThat(LocationFlags.split(null)).isEmpty();
    }

    @Test
    void haversineDistance() {
        // 0.01 degree of latitude ~ 1112 m
        assertThat(LocationFlags.distanceMeters(28.60, 77.20, 28.61, 77.20)).isBetween(1100.0, 1125.0);
    }
}
