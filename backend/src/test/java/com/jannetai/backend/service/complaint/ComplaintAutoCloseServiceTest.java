package com.jannetai.backend.service.complaint;

import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.enums.ActorType;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.service.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit GAP-028 (SRS 14.1 step 27): automatic closure after the grace period. */
@ExtendWith(MockitoExtension.class)
class ComplaintAutoCloseServiceTest {

    @Mock private ComplaintRepository complaintRepository;
    @Mock private ComplaintService complaintService;
    @Mock private AuditService auditService;

    @Test
    void resolvedComplaintsPastTheGracePeriodAreClosedBySystem() {
        ComplaintAutoCloseService service = new ComplaintAutoCloseService(complaintRepository, complaintService, auditService);
        ReflectionTestUtils.setField(service, "gracePeriodDays", 7);
        Complaint expired = Complaint.builder().complaintId(10L).status(ComplaintStatus.RESOLVED).build();
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        when(complaintRepository.findResolvedPastGracePeriod(cutoff.capture(), any())).thenReturn(List.of(expired));

        int closed = service.closeExpiredResolvedComplaints();

        assertThat(closed).isEqualTo(1);
        assertThat(cutoff.getValue()).isCloseTo(LocalDateTime.now().minusDays(7), within(5, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(expired.getStatus()).isEqualTo(ComplaintStatus.CLOSED);
        verify(complaintService).recordHistory(eq(expired), eq(ComplaintStatus.RESOLVED), eq(ComplaintStatus.CLOSED),
                isNull(), eq(ActorType.SYSTEM), anyString());
        verify(auditService).record(isNull(), eq("COMPLAINT_AUTO_CLOSED"), eq("COMPLAINT"), eq(10L), anyString());
    }
}
