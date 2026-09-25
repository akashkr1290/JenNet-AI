package com.jannetai.backend.service.complaint;

import com.jannetai.backend.entity.AuditLog;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.repository.AuditLogRepository;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit GAP-029: reputation moves with verified genuine / confirmed fraudulent complaints (SRS 15.1). */
@ExtendWith(MockitoExtension.class)
class ReputationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditLogRepository auditLogRepository;
    private ReputationService service;
    private User citizen;
    private Complaint complaint;

    @BeforeEach
    void setUp() {
        service = new ReputationService(userRepository, auditService, auditLogRepository);
        citizen = User.builder().userId(5L).reputationScore(100).build();
        complaint = Complaint.builder().complaintId(10L).citizen(citizen).build();
        lenient().when(auditLogRepository.findByEntityTypeAndEntityIdAndActionTypeOrderByCreatedAtAsc(any(), anyLong(), any()))
                .thenReturn(List.of());
    }

    @Test
    void verifiedGenuineComplaintRaisesTheScoreOnce() {
        service.onVerifiedGenuine(complaint);
        assertThat(citizen.getReputationScore()).isEqualTo(105);
        verify(auditService).record(eq(null), eq("REPUTATION_GENUINE_COMPLAINT"), eq("COMPLAINT"), eq(10L), anyString());

        when(auditLogRepository.findByEntityTypeAndEntityIdAndActionTypeOrderByCreatedAtAsc(
                "COMPLAINT", 10L, "REPUTATION_GENUINE_COMPLAINT")).thenReturn(List.of(AuditLog.builder().build()));
        service.onVerifiedGenuine(complaint);          // e.g. re-verified after an appeal
        assertThat(citizen.getReputationScore()).isEqualTo(105);
    }

    @Test
    void fraudRejectionLowersTheScoreButOtherReasonsDoNot() {
        service.onRejected(complaint, "INSUFFICIENT_EVIDENCE");
        assertThat(citizen.getReputationScore()).isEqualTo(100);
        verify(userRepository, never()).save(any());

        service.onRejected(complaint, "SPAM_OR_ABUSE");
        assertThat(citizen.getReputationScore()).isEqualTo(80);
    }

    @Test
    void anApprovedAppealGivesTheFraudPenaltyBack() {
        when(auditLogRepository.findByEntityTypeAndEntityIdAndActionTypeOrderByCreatedAtAsc(
                "COMPLAINT", 10L, "REPUTATION_FRAUDULENT_COMPLAINT")).thenReturn(List.of(AuditLog.builder().build()));
        citizen.setReputationScore(80);

        service.onFraudOverturned(complaint, "SPAM_OR_ABUSE");

        assertThat(citizen.getReputationScore()).isEqualTo(100);
    }

    @Test
    void scoreStaysWithinTheDatabaseRange() {
        assertThat(ReputationService.adjusted(10, -20)).isZero();
        assertThat(ReputationService.adjusted(998, 5)).isEqualTo(1000);
    }
}
