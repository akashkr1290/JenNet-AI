package com.jannetai.backend.service.complaint;

import com.jannetai.backend.dto.complaint.RatingRequest;
import com.jannetai.backend.dto.complaint.RatingResponse;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.ComplaintRating;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.exception.InvalidStateTransitionException;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.ComplaintRatingRepository;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gap-backlog Patch 13 (Sep 2026 audit): citizen resolution rating.
 * Deliberately a small, separate service rather than folded into the
 * already-large ComplaintService - rating is a standalone citizen action
 * (SRS didn't cover it, per PHASE_HANDOFF.md instruction to record
 * assumptions/additions) that doesn't touch the complaint's own state
 * machine at all, unlike reopen/appeal.
 */
@Service
@RequiredArgsConstructor
public class ComplaintRatingService {

    private final ComplaintRatingRepository ratingRepository;
    private final ComplaintRepository complaintRepository;
    private final AuditService auditService;

    @Transactional
    public RatingResponse rate(User citizen, Long complaintId, RatingRequest request) {
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found: " + complaintId));

        if (!complaint.getCitizen().getUserId().equals(citizen.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may only rate your own complaints");
        }
        if (complaint.getStatus() != ComplaintStatus.RESOLVED && complaint.getStatus() != ComplaintStatus.CLOSED) {
            throw new InvalidStateTransitionException(
                    "Only a Resolved or Closed complaint can be rated (current status: " + complaint.getStatus() + ")");
        }
        // Belt-and-suspenders alongside V19's own unique constraint on
        // complaint_id - this check gives a clean 409 instead of the
        // DataIntegrityViolationException GlobalExceptionHandler would
        // otherwise translate to a generic 500-adjacent response.
        if (ratingRepository.existsByComplaint_ComplaintId(complaintId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This complaint has already been rated");
        }

        ComplaintRating rating = ComplaintRating.builder()
                .complaint(complaint)
                .citizen(citizen)
                .rating(request.rating())
                .comment(request.comment())
                .build();
        rating = ratingRepository.save(rating);

        auditService.record(citizen, "COMPLAINT_RATED", "COMPLAINT", complaintId,
                "{\"rating\":" + request.rating() + "}");

        return RatingResponse.from(rating);
    }

    @Transactional(readOnly = true)
    public RatingResponse getRating(User requester, Long complaintId) {
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found: " + complaintId));
        // Same ownership rule as submitting a rating - a citizen can only
        // read back their own complaint's rating. Staff visibility (e.g.
        // for a department performance view) is a real future use case
        // but not one any current screen asked for - not added
        // speculatively.
        if (!complaint.getCitizen().getUserId().equals(requester.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This complaint is not in your scope");
        }
        return ratingRepository.findByComplaint_ComplaintId(complaintId)
                .map(RatingResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("No rating found for complaint " + complaintId));
    }
}
