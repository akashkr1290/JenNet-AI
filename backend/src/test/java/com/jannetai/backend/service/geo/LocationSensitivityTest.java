package com.jannetai.backend.service.geo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit GAP-033 (SRS 15.8 location-sensitivity flags). NOT EXECUTED here via Maven. */
class LocationSensitivityTest {

    @Test
    void flagsOnlyZonesWhoseRadiusContainsThePoint() {
        var zones = List.of(
                new LocationSensitivity.Zone("SCHOOL", 19.0760, 72.8777, 200),
                new LocationSensitivity.Zone("HOSPITAL", 19.0900, 72.8777, 300),
                new LocationSensitivity.Zone("HIGH_TRAFFIC_ROAD", 19.0765, 72.8777, 100));
        // ~55 m north of the school centre: inside the school and road circles, ~1.5 km from the hospital
        var flags = LocationSensitivity.flagsFor(19.0765, 72.8777, zones);
        assertThat(flags.nearSchool()).isTrue();
        assertThat(flags.highTrafficRoad()).isTrue();
        assertThat(flags.nearHospital()).isFalse();
        assertThat(LocationSensitivity.flagsFor(19.2, 72.9, zones)).isEqualTo(LocationSensitivity.Flags.NONE);
    }

    @Test
    void distanceIsGreatCircle() {
        // one degree of latitude is about 111.2 km
        assertThat(LocationSensitivity.distanceMeters(19, 72, 20, 72)).isBetween(111_000.0, 111_400.0);
    }
}
