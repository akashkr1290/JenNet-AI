package com.jannetai.backend.service.complaint;

import com.jannetai.backend.dto.complaint.AppealRequest;
import com.jannetai.backend.dto.complaint.AppealResponse;
import com.jannetai.backend.dto.complaint.AppealReviewRequest;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.ComplaintAppeal;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.AppealStatus;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.exception.InvalidStateTransitionException;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.ComplaintAppealRepository;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Gap-backlog Patch 12 (Sep 2026 audit): a citizen's appeal of a REJECTED
 * complaint's decision.
 *
 * <p>Audit GAP-030 (SRS 14.1 step 28, 14.3 "A Rejected complaint may be
 * appealed exactly once ... a second rejection on appeal is final"; SRS
 * 15.10/27.1 role scoping). Replaces the earlier documented decision that an
 * approval changed nothing:
 * <ul>
 *   <li>a complaint can be appealed once - any existing appeal (pending,
 *       approved or denied) blocks another;</li>
 *   <li>APPROVED sends the complaint back to AI_PROCESSING - the Verification
 *       Team's queue - through the dedicated
 *       {@link ComplaintStateMachine#assertAppealReverificationAllowed}
 *       transition (REJECTED stays terminal for every other path), with a
 *       status-history entry, the citizen notification, and reversal of a
 *       fraud reputation penalty; a new rejection after that is final because
 *       the one appeal is used up;</li>
 *   <li>a DEPARTMENT_HEAD sees and decides only appeals of complaints routed to
 *       their own department; Verification Team / Admin / Super Admin see all.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ComplaintAppealService {

    private final ComplaintAppealRepository appealRepository;
    private final ComplaintRepository complaintRepository;
    private final AuditService auditService;
    private final NotificationService notificationService; // audit GAP-023: appeal outcome alert
    private final ComplaintService complaintService;       // audit GAP-030: status history for re-verification
    private final ReputationService reputationService;     // audit GAP-030/029: undo an overturned fraud penalty

    @Transactional
    public AppealResponse submit(User citizen, Long complaintId, AppealRequest request) {
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found: " + complaintId));

        if (!complaint.getCitizen().getUserId().equals(citizen.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may only appeal your own complaints");
        }
        if (complaint.getStatus() != ComplaintStatus.REJECTED) {
            throw new InvalidStateTransitionException(
                    "Only a Rejected complaint can be appealed (current status: " + complaint.getStatus() + ")");
        }
        // Belt-and-suspenders alongside V20's own unique constraint on
        // pending_complaint_id - same reasoning as ComplaintRatingService's
        // pre-check: a clean 409 instead of a generic constraint-violation
        // response.
        if (appealRepository.existsByComplaint_ComplaintIdAndStatus(complaintId, AppealStatus.PENDING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This complaint already has a pending appeal");
        }
        // Audit GAP-030 (SRS 14.3): exactly one appeal per complaint.
        if (appealRepository.existsByComplaint_ComplaintId(complaintId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This complaint has already been appealed once; the decision on appeal is final");
        }

        ComplaintAppeal appeal = ComplaintAppeal.builder()
                .complaint(complaint)
                .citizen(citizen)
                .reason(request.reason())
                .status(AppealStatus.PENDING)
                .pendingComplaintId(complaintId)
                .build();
        appeal = appealRepository.save(appeal);

        auditService.record(citizen, "COMPLAINT_APPEAL_SUBMITTED", "COMPLAINT", complaintId, null);

        return AppealResponse.from(appeal);
    }

    @Transactional(readOnly = true)
    public List<AppealResponse> listForComplaint(User requester, Long complaintId) {
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found: " + complaintId));
        boolean isOwner = complaint.getCitizen().getUserId().equals(requester.getUserId());
        boolean isStaff = requester.getRole() != Role.CITIZEN;
        if (!isOwner && !isStaff) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This complaint is not in your scope");
        }
        return appealRepository.findByComplaint_ComplaintIdOrderByCreatedAtDesc(complaintId)
                .stream().map(AppealResponse::from).toList();
    }

    /** Staff review queue - PENDING appeals, oldest first. */
    @Transactional(readOnly = true)
    public List<AppealResponse> listPending() {
        return appealRepository.findByStatusOrderByCreatedAtAsc(AppealStatus.PENDING)
                .stream().map(AppealResponse::from).toList();
    }

    /**
     * Audit GAP-030: the queue as the given reviewer may see it - a Department
     * Head only gets appeals of complaints routed to their own department.
     */
    @Transactional(readOnly = true)
    public List<AppealResponse> listPending(User reviewer) {
        return appealRepository.findByStatusOrderByCreatedAtAsc(AppealStatus.PENDING).stream()
                .filter(appeal -> inReviewScope(reviewer, appeal.getComplaint()))
                .map(AppealResponse::from).toList();
    }

    static boolean inReviewScope(User reviewer, Complaint complaint) {
        if (reviewer.getRole() != Role.DEPARTMENT_HEAD) {
            return true;
        }
        return reviewer.getDepartment() != null && complaint.getDepartment() != null
                && reviewer.getDepartment().getDepartmentId().equals(complaint.getDepartment().getDepartmentId());
    }

    @Transactional
    public AppealResponse review(User staff, Long appealId, AppealReviewRequest request) {
        if (request.decision() == AppealStatus.PENDING) {
            throw new InvalidStateTransitionException("A review decision must be APPROVED or DENIED, not PENDING");
        }
        ComplaintAppeal appeal = appealRepository.findById(appealId)
                .orElseThrow(() -> new ResourceNotFoundException("Appeal not found: " + appealId));
        if (appeal.getStatus() != AppealStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "This appeal has already been reviewed (status: " + appeal.getStatus() + ")");
        }
        Complaint complaint = appeal.getComplaint();
        if (!inReviewScope(staff, complaint)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This appeal belongs to a complaint outside your department");
        }
        if (request.decision() == AppealStatus.APPROVED) {
            // Validate before anything is written.
            ComplaintStateMachine.assertAppealReverificationAllowed(complaint.getStatus(), staff.getRole());
        }

        appeal.setStatus(request.decision());
        appeal.setPendingComplaintId(null); // frees the one-pending-appeal slot (V20)
        appeal.setReviewedBy(staff);
        appeal.setReviewedAt(LocalDateTime.now());
        appeal.setReviewNote(request.note());
        appeal = appealRepository.save(appeal);

        auditService.record(staff, "COMPLAINT_APPEAL_" + request.decision().name(), "COMPLAINT",
                appeal.getComplaint().getComplaintId(), null);

        if (request.decision() == AppealStatus.APPROVED) {
            // Audit GAP-030: back to the Verification Team for re-verification.
            ComplaintStatus previous = complaint.getStatus();
            String rejectedFor = complaint.getRejectionReasonCode();
            complaint.setStatus(ComplaintStatus.AI_PROCESSING);
            complaint.setRejectionReasonCode(null);
            complaint = complaintRepository.save(complaint);
            complaintService.recordHistory(complaint, previous, ComplaintStatus.AI_PROCESSING, staff,
                    ComplaintStateMachine.actorTypeFor(staff.getRole()),
                    "Appeal approved - sent back for re-verification"
                            + (request.note() == null || request.note().isBlank() ? "" : ": " + request.note().strip()));
            reputationService.onFraudOverturned(complaint, rejectedFor);
        }
        notificationService.notifyAppealDecided(appeal.getComplaint(),
                request.decision() == AppealStatus.APPROVED, request.note());

        return AppealResponse.from(appeal);
    }
}
