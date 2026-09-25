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
}
