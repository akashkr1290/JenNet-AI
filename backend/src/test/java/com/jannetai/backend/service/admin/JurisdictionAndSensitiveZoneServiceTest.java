package com.jannetai.backend.service.admin;

import com.jannetai.backend.client.ai.AiLocationSensitivityFlags;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Location;
import com.jannetai.backend.entity.SensitiveZone;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.LocationSource;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.SensitiveZoneType;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.LocationRepository;
import com.jannetai.backend.repository.SensitiveZoneRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.WardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit GAP-031 (out-of-jurisdiction review) and GAP-033 (sensitive zones). NOT EXECUTED here via Maven. */
@ExtendWith(MockitoExtension.class)
class JurisdictionAndSensitiveZoneServiceTest {

    @Mock private ComplaintRepository complaintRepository;
    @Mock private LocationRepository locationRepository;
    @Mock private WardService wardService;
    @Mock private AuditService auditService;
    @Mock private SensitiveZoneRepository sensitiveZoneRepository;

    private final User admin = User.builder().userId(1L).role(Role.ADMIN).build();

    @Test
    void acceptingAssignsTheWardClearsTheFlagAndAudits() {
        var review = new OutOfJurisdictionReviewService(complaintRepository, locationRepository, wardService, auditService);
        Location location = Location.builder().latitude(new BigDecimal("40.0")).longitude(new BigDecimal("75.0"))
                .source(LocationSource.DEVICE_GPS).outOfJurisdiction(true).build();
        Complaint complaint = Complaint.builder().complaintId(5L).referenceNumber("JN-2026-000005")
                .status(ComplaintStatus.VERIFIED).location(location).build();
        Ward ward = Ward.builder().wardId(3L).name("Ward 3").isActive(true).build();
        when(complaintRepository.findById(5L)).thenReturn(Optional.of(complaint));
        when(wardService.requireActiveWardEntity(3L)).thenReturn(ward);

        var result = review.accept(admin, 5L, 3L);

        assertThat(location.getOutOfJurisdiction()).isFalse();
        assertThat(location.getWard()).isEqualTo(ward);
        assertThat(result.wardName()).isEqualTo("Ward 3");
        verify(locationRepository).save(location);
        verify(auditService).record(eq(admin), eq("OUT_OF_JURISDICTION_ACCEPTED"), eq("COMPLAINT"), eq(5L), anyString());
    }

    @Test
    void acceptingAnUnflaggedComplaintIsAConflict() {
        var review = new OutOfJurisdictionReviewService(complaintRepository, locationRepository, wardService, auditService);
        Complaint complaint = Complaint.builder().complaintId(6L)
                .location(Location.builder().outOfJurisdiction(false).build()).build();
        when(complaintRepository.findById(6L)).thenReturn(Optional.of(complaint));
        assertThatThrownBy(() -> review.accept(admin, 6L, 3L)).isInstanceOf(ResponseStatusException.class);
        verify(locationRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void zoneFlagsComeFromActiveZonesAndNeverFromApproximateLocations() {
        var zones = new SensitiveZoneService(sensitiveZoneRepository, auditService);
        when(sensitiveZoneRepository.findByIsActiveTrue()).thenReturn(List.of(SensitiveZone.builder()
                .zoneType(SensitiveZoneType.SCHOOL).latitude(new BigDecimal("19.076000"))
                .longitude(new BigDecimal("72.877700")).radiusMeters(200).isActive(true).build()));
        Location nearSchool = Location.builder().latitude(new BigDecimal("19.076500")).longitude(new BigDecimal("72.877700"))
                .source(LocationSource.DEVICE_GPS).build();

        assertThat(zones.flagsFor(nearSchool)).isEqualTo(new AiLocationSensitivityFlags(true, false, false));

        Location approximate = Location.builder().latitude(new BigDecimal("19.076500")).longitude(new BigDecimal("72.877700"))
                .source(LocationSource.WARD_FALLBACK).build();
        assertThat(zones.flagsFor(approximate)).isEqualTo(AiLocationSensitivityFlags.NONE_AVAILABLE);
        assertThat(zones.flagsFor(null)).isEqualTo(AiLocationSensitivityFlags.NONE_AVAILABLE);
    }
}
