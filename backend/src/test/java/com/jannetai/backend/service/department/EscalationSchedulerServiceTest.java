package com.jannetai.backend.service.department;

import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Severity;
import com.jannetai.backend.repository.AuditLogRepository;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.admin.PlatformSettingsService;
import com.jannetai.backend.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit GAP-027: escalation and warnings use the persisted clock. */
@ExtendWith(MockitoExtension.class)
class EscalationSchedulerServiceTest {

    @Mock private ComplaintRepository complaintRepository;
    @Mock private AuditService auditService;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private PlatformSettingsService platformSettingsService;
    @Mock private SlaPolicy slaPolicy;
    @Mock private NotificationService notificationService;

    private EscalationSchedulerService service;

    @BeforeEach
    void setUp() {
        service = new EscalationSchedulerService(complaintRepository, auditService, auditLogRepository,
                platformSettingsService, slaPolicy, notificationService);
        lenient().when(complaintRepository.findClockedStatusWithoutSlaClock(any())).thenReturn(List.of());
    }

    private Complaint clocked(long id, int hours, LocalDateTime due) {
        return Complaint.builder().complaintId(id).status(ComplaintStatus.IN_PROGRESS).severity(Severity.HIGH)
                .isEscalated(false).slaHours(hours).slaDueAt(due)
                .assignedOfficer(User.builder().userId(7L).build()).build();
    }

    @Test
    void breachedComplaintIsEscalatedFromItsStoredWindow() {
        Complaint breached = clocked(1L, 72, LocalDateTime.now().minusMinutes(5));
        when(complaintRepository.findSlaDueBreaches(any(), any())).thenReturn(List.of(breached));

        service.sweepForSlaBreaches();

        assertThat(breached.getIsEscalated()).isTrue();
        assertThat(breached.getEscalatedAt()).isNotNull();
        verify(notificationService).notifyComplaintEscalated(breached, 72L);
        verify(auditService).record(isNull(), eq("COMPLAINT_ESCALATED_SLA_BREACH"), eq("COMPLAINT"), eq(1L), anyString());
    }

    @Test
    void preV27ComplaintsGetAClockStartedAtTheirOldBaseline() {
        LocalDateTime updated = LocalDateTime.now().minusHours(10);
        Complaint legacy = Complaint.builder().complaintId(2L).status(ComplaintStatus.ASSIGNED)
                .severity(Severity.HIGH).updatedAt(updated).build();
        when(complaintRepository.findClockedStatusWithoutSlaClock(any())).thenReturn(List.of(legacy));
        when(complaintRepository.findSlaDueBreaches(any(), any())).thenReturn(List.of());

        service.sweepForSlaBreaches();

        verify(slaPolicy).startClock(legacy, updated);
        verify(complaintRepository).save(legacy);
    }

    @Test
    void warningIsSentOnceAt80Percent() {
        Complaint warn = clocked(3L, 72, LocalDateTime.now().plusHours(5));
        when(complaintRepository.findSlaWarningsDue(any(), any())).thenReturn(List.of(warn));
        when(auditLogRepository.findByEntityTypeAndEntityIdAndActionTypeOrderByCreatedAtAsc(
                "COMPLAINT", 3L, "COMPLAINT_SLA_WARNING_SENT")).thenReturn(List.of());

        service.sweepForSlaWarnings();

        verify(notificationService).notifySlaBreachWarning(warn, warn.getAssignedOfficer());
    }

    @Test
    void nothingDueMeansNothingEscalated() {
        when(complaintRepository.findSlaDueBreaches(any(), any())).thenReturn(List.of());
        service.sweepForSlaBreaches();
        verify(notificationService, never()).notifyComplaintEscalated(any(), anyLong());
    }
}
