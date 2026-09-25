package com.jannetai.backend.service.complaint;

import com.jannetai.backend.client.ai.AiDuplicateCheckRequest;
import com.jannetai.backend.client.ai.AiServiceClient;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Image;
import com.jannetai.backend.entity.Location;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.ImageType;
import com.jannetai.backend.entity.enums.LocationSource;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.ImageRepository;
import com.jannetai.backend.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Audit GAP-008: duplicate detection no longer requires a ward (SRS 15.6: 50 m + 30 days). */
@ExtendWith(MockitoExtension.class)
class DuplicateDetectionServiceTest {

    @Mock private ComplaintRepository complaintRepository;
    @Mock private ImageRepository imageRepository;
    @Mock private StorageService storageService;
    @Mock private AiServiceClient aiServiceClient;

    private DuplicateDetectionService service;

    @BeforeEach
    void setUp() {
        service = new DuplicateDetectionService(complaintRepository, imageRepository, storageService, aiServiceClient);
        ReflectionTestUtils.setField(service, "candidateWindowDays", 30);
        ReflectionTestUtils.setField(service, "maxCandidates", 10);
        ReflectionTestUtils.setField(service, "searchRadiusMeters", 100.0);
        lenient().when(imageRepository.findFirstByComplaint_ComplaintIdAndImageTypeOrderByUploadedAtAsc(anyLong(), eq(ImageType.BEFORE)))
                .thenAnswer(inv -> Optional.of(Image.builder().storageKey("k" + inv.getArgument(0)).imageType(ImageType.BEFORE).build()));
        lenient().when(storageService.load(any())).thenReturn(new byte[] {1, 2, 3});
    }

    private static Complaint complaint(long id, Location location) {
        return Complaint.builder().complaintId(id).referenceNumber("JN-" + id).status(ComplaintStatus.AI_PROCESSING)
                .location(location).createdAt(LocalDateTime.now().minusHours(id)).build();
    }

    private static Location gps(String lat, String lng, Ward ward) {
        return Location.builder().latitude(new BigDecimal(lat)).longitude(new BigDecimal(lng))
                .source(LocationSource.DEVICE_GPS).ward(ward).outOfJurisdiction(false).build();
    }

    @Test
    void wardlessGpsComplaintIsCheckedAgainstNearbyComplaints() {
        Complaint fresh = complaint(1, gps("12.971600", "77.594600", null));
        Complaint nearby = complaint(2, gps("12.971800", "77.594700", null)); // ~25 m away, also ward-less
        when(complaintRepository.findDuplicateCandidatesNear(any(), any(), any(), any(), eq(1L), any(), any(), any()))
                .thenReturn(List.of(nearby));

        service.check(fresh);

        ArgumentCaptor<AiDuplicateCheckRequest> request = ArgumentCaptor.forClass(AiDuplicateCheckRequest.class);
        verify(aiServiceClient).checkDuplicate(request.capture());
        assertThat(request.getValue().candidates()).extracting(c -> c.complaintId()).containsExactly(2L);
        assertThat(request.getValue().latitude()).isEqualByComparingTo("12.971600");
        verify(complaintRepository, never()).findDuplicateCandidates(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void searchBoxIsCentredOnThePointAndWiderThanFiftyMetres() {
        Complaint fresh = complaint(1, gps("12.971600", "77.594600", null));
        ArgumentCaptor<BigDecimal> minLat = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> maxLat = ArgumentCaptor.forClass(BigDecimal.class);
        when(complaintRepository.findDuplicateCandidatesNear(minLat.capture(), maxLat.capture(), any(), any(),
                eq(1L), any(), any(), any())).thenReturn(List.of());

        service.check(fresh);

        double halfSpanMetres = (maxLat.getValue().doubleValue() - minLat.getValue().doubleValue()) / 2 * 111_195;
        assertThat(halfSpanMetres).isBetween(99.0, 101.0);
    }

    @Test
    void gpsComplaintWithAWardMergesNearbyAndSameWardCandidatesWithoutRepeats() {
        Ward ward = Ward.builder().wardId(5L).name("W5").build();
        Complaint fresh = complaint(1, gps("12.971600", "77.594600", ward));
        Complaint nearby = complaint(2, gps("12.971800", "77.594700", ward));
        Complaint sameWard = complaint(3, Location.builder().latitude(new BigDecimal("12.9")).longitude(new BigDecimal("77.5"))
                .source(LocationSource.WARD_FALLBACK).ward(ward).outOfJurisdiction(false).build());
        when(complaintRepository.findDuplicateCandidatesNear(any(), any(), any(), any(), eq(1L), any(), any(), any()))
                .thenReturn(List.of(nearby));
        when(complaintRepository.findDuplicateCandidates(eq(5L), eq(1L), any(), any(), any()))
                .thenReturn(List.of(nearby, sameWard));

        service.check(fresh);

        ArgumentCaptor<AiDuplicateCheckRequest> request = ArgumentCaptor.forClass(AiDuplicateCheckRequest.class);
        verify(aiServiceClient).checkDuplicate(request.capture());
        assertThat(request.getValue().candidates()).extracting(c -> c.complaintId()).containsExactly(2L, 3L);
    }

    @Test
    void wardFallbackComplaintUsesOnlyItsWardAndSendsNoCoordinates() {
        Ward ward = Ward.builder().wardId(5L).name("W5").build();
        Complaint fresh = complaint(1, Location.builder().latitude(new BigDecimal("12.9")).longitude(new BigDecimal("77.5"))
                .source(LocationSource.WARD_FALLBACK).ward(ward).outOfJurisdiction(false).build());
        when(complaintRepository.findDuplicateCandidates(eq(5L), eq(1L), any(), any(), any())).thenReturn(List.of());

        service.check(fresh);

        verify(complaintRepository, never()).findDuplicateCandidatesNear(any(), any(), any(), any(), anyLong(), any(), any(), any());
        ArgumentCaptor<AiDuplicateCheckRequest> request = ArgumentCaptor.forClass(AiDuplicateCheckRequest.class);
        verify(aiServiceClient).checkDuplicate(request.capture());
        assertThat(request.getValue().latitude()).isNull();
    }

    @Test
    void noPositionAndNoWardSkipsTheCheck() {
        Complaint fresh = complaint(1, null);

        assertThat(service.check(fresh)).isNull();
        verifyNoInteractions(aiServiceClient);
    }
}
