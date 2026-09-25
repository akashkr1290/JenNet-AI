package com.jannetai.backend.service.complaint;

import com.jannetai.backend.dto.complaint.AppealRequest;
import com.jannetai.backend.dto.complaint.AppealReviewRequest;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.ComplaintAppeal;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.AppealStatus;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.repository.ComplaintAppealRepository;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit GAP-030 (SRS 14.3 "appealed exactly once"; 14.1 step 28; role scoping). */
@ExtendWith(MockitoExtension.class)
class ComplaintAppealServiceTest {

    @Mock private ComplaintAppealRepository appealRepository;
    @Mock private ComplaintRepository complaintRepository;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private ComplaintService complaintService;
    @Mock private ReputationService reputationService;
    private ComplaintAppealService service;

    private final Department roads = Department.builder().departmentId(1L).name("Roads").build();
    private final Department water = Department.builder().departmentId(2L).name("Water").build();
    private final User citizen = User.builder().userId(5L).role(Role.CITIZEN).build();

    @BeforeEach
    void setUp() {
        service = new ComplaintAppealService(appealRepository, complaintRepository, auditService, notificationService,
                complaintService, reputationService);
        lenient().when(appealRepository.save(any(ComplaintAppeal.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(complaintRepository.save(any(Complaint.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Complaint rejected(Department department) {
        return Complaint.builder().complaintId(10L).citizen(citizen).status(ComplaintStatus.REJECTED)
                .rejectionReasonCode("SPAM_OR_ABUSE").department(department).build();
    }

    private ComplaintAppeal pendingAppeal(Complaint complaint) {
        return ComplaintAppeal.builder().appealId(3L).complaint(complaint).citizen(citizen)
                .status(AppealStatus.PENDING).pendingComplaintId(10L).build();
    }

    @Test
    void aComplaintCanBeAppealedOnlyOnce() {
        when(complaintRepository.findById(10L)).thenReturn(Optional.of(rejected(roads)));
        when(appealRepository.existsByComplaint_ComplaintIdAndStatus(10L, AppealStatus.PENDING)).thenReturn(false);
        when(appealRepository.existsByComplaint_ComplaintId(10L)).thenReturn(true); // an earlier, DENIED appeal

        assertThatThrownBy(() -> service.submit(citizen, 10L, new AppealRequest("Please look again at the photo")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already been appealed once");
        verify(appealRepository, never()).save(any());
    }

    @Test
    void approvalSendsTheComplaintBackToTheVerificationQueue() {
        Complaint complaint = rejected(roads);
        when(appealRepository.findById(3L)).thenReturn(Optional.of(pendingAppeal(complaint)));
        User verifier = User.builder().userId(8L).role(Role.VERIFICATION_TEAM).build();

        service.review(verifier, 3L, new AppealReviewRequest(AppealStatus.APPROVED, "Photo is genuine"));

        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.AI_PROCESSING);
        assertThat(complaint.getRejectionReasonCode()).isNull();
        verify(complaintService).recordHistory(eq(complaint), eq(ComplaintStatus.REJECTED),
                eq(ComplaintStatus.AI_PROCESSING), eq(verifier), any(), anyString());
        verify(reputationService).onFraudOverturned(complaint, "SPAM_OR_ABUSE");
        verify(notificationService).notifyAppealDecided(complaint, true, "Photo is genuine");
    }

    @Test
    void denialLeavesTheComplaintRejected() {
        Complaint complaint = rejected(roads);
        when(appealRepository.findById(3L)).thenReturn(Optional.of(pendingAppeal(complaint)));

        service.review(User.builder().userId(8L).role(Role.ADMIN).build(), 3L,
                new AppealReviewRequest(AppealStatus.DENIED, null));

        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.REJECTED);
        verify(complaintService, never()).recordHistory(any(), any(), any(), any(), any(), any());
    }

    @Test
    void departmentHeadCannotDecideAnotherDepartmentsAppeal() {
        Complaint complaint = rejected(water);
        when(appealRepository.findById(3L)).thenReturn(Optional.of(pendingAppeal(complaint)));
        User roadsHead = User.builder().userId(6L).role(Role.DEPARTMENT_HEAD).department(roads).build();

        assertThatThrownBy(() -> service.review(roadsHead, 3L, new AppealReviewRequest(AppealStatus.APPROVED, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.REJECTED);
    }

    @Test
    void departmentHeadQueueShowsOnlyTheirDepartment() {
        when(appealRepository.findByStatusOrderByCreatedAtAsc(AppealStatus.PENDING))
                .thenReturn(List.of(pendingAppeal(rejected(roads)), pendingAppeal(rejected(water))));
        User roadsHead = User.builder().userId(6L).role(Role.DEPARTMENT_HEAD).department(roads).build();
        User verifier = User.builder().userId(8L).role(Role.VERIFICATION_TEAM).build();

        assertThat(service.listPending(roadsHead)).hasSize(1);
        assertThat(service.listPending(verifier)).hasSize(2);
    }
}
