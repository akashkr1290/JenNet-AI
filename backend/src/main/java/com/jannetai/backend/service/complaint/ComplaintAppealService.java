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
 * <p>DECISION (documented per PHASE_HANDOFF.md's own "log assumptions/
 * additions" instruction, same as every other undocumented-in-the-SRS
 * addition this project has made): approving an appeal does NOT, by
 * itself, transition the complaint out of REJECTED. ComplaintStateMachine
 * treats REJECTED as terminal by explicit SRS-derived design ("Rejected
 * is only reachable prior to In Progress" - no path back in), and that
 * table is the locked, single source of truth for every status change
 * this codebase makes; this service deliberately does not bypass it.
 * {@link #review} therefore only records the appeal's own PENDING ->
 * APPROVED/DENIED decision (queryable, audited) - actually reopening an
 * approved-appeal complaint for re-review is a real follow-on action
 * still requiring a deliberate decision (either a new
 * ComplaintStateMachine edge, e.g. REJECTED -> VERIFIED, added by
 * whoever owns that locked table, or an out-of-band admin action) rather
 * than one made silently here. This is the same "explicit, not silent"
 * standard PROJECT_INTEGRATION.md Section 6 already holds every other
 * SRS gap to.
 */
@Service
@RequiredArgsConstructor
public class ComplaintAppealService {

    private final ComplaintAppealRepository appealRepository;
    private final ComplaintRepository complaintRepository;
    private final AuditService auditService;

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

    /** Staff review queue (SRS-undocumented addition, same pattern as the officer queue) - all PENDING appeals, oldest first. */
    @Transactional(readOnly = true)
    public List<AppealResponse> listPending() {
        return appealRepository.findByStatusOrderByCreatedAtAsc(AppealStatus.PENDING)
                .stream().map(AppealResponse::from).toList();
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

        appeal.setStatus(request.decision());
        appeal.setPendingComplaintId(null); // frees the one-pending-appeal slot (V20)
        appeal.setReviewedBy(staff);
        appeal.setReviewedAt(LocalDateTime.now());
        appeal.setReviewNote(request.note());
        appeal = appealRepository.save(appeal);

        auditService.record(staff, "COMPLAINT_APPEAL_" + request.decision().name(), "COMPLAINT",
                appeal.getComplaint().getComplaintId(), null);

        return AppealResponse.from(appeal);
    }
}
