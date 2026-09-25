package com.jannetai.backend.controller;

import com.jannetai.backend.dto.complaint.AppealRequest;
import com.jannetai.backend.dto.complaint.AppealResponse;
import com.jannetai.backend.dto.complaint.AppealReviewRequest;
import com.jannetai.backend.dto.complaint.AssignmentRequest;
import com.jannetai.backend.dto.complaint.BudgetRejectionRequest;
import com.jannetai.backend.dto.complaint.ClassificationOverrideRequest;
import com.jannetai.backend.dto.complaint.ComplaintResponse;
import com.jannetai.backend.dto.complaint.ComplaintSummaryResponse;
import com.jannetai.backend.dto.complaint.InternalNoteRequest;
import com.jannetai.backend.dto.complaint.RatingRequest;
import com.jannetai.backend.dto.complaint.RatingResponse;
import com.jannetai.backend.dto.complaint.ReopenRequest;
import com.jannetai.backend.dto.complaint.StatusUpdateRequest;
import com.jannetai.backend.dto.complaint.VerificationDecisionRequest;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.LocationSource;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.complaint.AiProcessingDispatcher;
import com.jannetai.backend.service.complaint.ImageQualityGate;
import com.jannetai.backend.service.complaint.ComplaintAppealService;
import com.jannetai.backend.service.complaint.ComplaintRatingService;
import com.jannetai.backend.service.complaint.ComplaintService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

/**
 * Phase 6 (Complaint Module, SRS 15.3 / Table 23). Every action delegates
 * straight to ComplaintService - this class only handles HTTP binding,
 * @PreAuthorize role gates, and status codes.
 */
@RestController
@RequestMapping("/api/v1/complaints")
@RequiredArgsConstructor
@Tag(name = "Complaints", description = "Complaint submission, tracking, and the Phase 6 manual verification override")
public class ComplaintController {

    private final ComplaintService complaintService;
    private final AiProcessingDispatcher aiProcessingDispatcher; // audit GAP-010
    private final ImageQualityGate imageQualityGate;             // audit GAP-032
    private final ComplaintRatingService ratingService;
    private final ComplaintAppealService appealService;

    /**
     * Audit GAP-010: {@link ComplaintService#create} commits the complaint at
     * AI_PROCESSING and the AI pipeline is queued (AiProcessingDispatcher) instead
     * of being run inline - the citizen gets 201 immediately (SRS NFR:
     * acknowledgement under 2 s), and ai-service failures are retried in the
     * background (3 attempts, then the Verification Team). The response shows
     * AI_PROCESSING; the routed status appears on GET /complaints/{id} and in the
     * citizen's notifications. With app.ai-processing.async-enabled=false the
     * attempt runs before responding and the routed state is returned, as before.
     */
    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CITIZEN')")
    public ComplaintResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                     @RequestPart("photo") MultipartFile photo,
                                     @RequestParam(required = false) String description,
                                     // Remaining-gaps item 3: optional when a ward is chosen instead (GPS unavailable)
                                     @RequestParam(required = false) BigDecimal latitude,
                                     @RequestParam(required = false) BigDecimal longitude,
                                     @RequestParam(required = false) Long wardId,
                                     @RequestParam(required = false) LocationSource locationSource) {
        // Audit GAP-032 (SRS 21.3): an unusable photo is rejected (422, retake
        // prompt) BEFORE anything is stored.
        imageQualityGate.check(photo);
        ComplaintResponse created = complaintService.create(principal.getUser(), photo, description, latitude,
                longitude, wardId, locationSource);
        boolean ranInline = aiProcessingDispatcher.submit(created.complaintId());
        return ranInline ? complaintService.getDetail(principal.getUser(), created.complaintId()) : created;
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ComplaintResponse getDetail(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return complaintService.getDetail(principal.getUser(), id);
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Page<ComplaintSummaryResponse> list(@AuthenticationPrincipal UserPrincipal principal,
                                                @RequestParam(required = false) ComplaintStatus status,
                                                @RequestParam(required = false) ComplaintCategory category,
                                                @RequestParam(required = false) Long departmentId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int pageSize,
                                                // audit GAP-040: NEWEST (default) | SEVERITY | SLA_DUE
                                                @RequestParam(defaultValue = "NEWEST") com.jannetai.backend.dto.complaint.ComplaintSort sort) {
        return complaintService.list(principal.getUser(), status, category, departmentId, page, pageSize, sort);
    }

    /** The Phase 6 approved manual Verification Team override - see ComplaintService.verify's Javadoc. */
    @PatchMapping("/{id}/verify")
    @PreAuthorize("hasAnyRole('VERIFICATION_TEAM', 'ADMIN', 'SUPER_ADMIN')")
    public ComplaintResponse verify(@AuthenticationPrincipal UserPrincipal principal,
                                     @PathVariable Long id,
                                     @Valid @RequestBody VerificationDecisionRequest request) {
        return complaintService.verify(principal.getUser(), id, request);
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasRole('CITIZEN')")
    public ComplaintResponse reopen(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id,
                                    @Valid @RequestBody(required = false) ReopenRequest request) {
        return complaintService.reopen(principal.getUser(), id, request != null ? request.reason() : null);
    }

    /**
     * Generic downstream transition action (SRS Table 23 + 17.3 "Officer
     * Status Update Form"). Phase 12: converted to multipart/form-data so
     * the optional/conditional {@code after_photo} field can be accepted
     * - see StatusUpdateRequest's Javadoc and
     * ComplaintService.updateStatus for the SRS Table 8 conditional
     * validation (note/after_photo required for certain target statuses).
     */
    @PatchMapping(value = "/{id}/status", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('GOVERNMENT_OFFICER', 'MAINTENANCE_TEAM', 'DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public ComplaintResponse updateStatus(@AuthenticationPrincipal UserPrincipal principal,
                                           @PathVariable Long id,
                                           @RequestParam ComplaintStatus newStatus,
                                           @RequestParam(required = false) String note,
                                           // Audit GAP-052: mandatory for newStatus=REJECTED (validated in the service)
                                           @RequestParam(required = false) String rejectionReasonCode,
                                           @RequestPart(value = "afterPhoto", required = false) MultipartFile afterPhoto) {
        return complaintService.updateStatus(principal.getUser(), id,
                new StatusUpdateRequest(newStatus, note, rejectionReasonCode), afterPhoto);
    }

    /**
     * Phase 12 (SRS 15.8 Features: "manual override by Officer/Department
     * Head with justification"; SRS 16.2 Complaint Detail (Officer View)
     * "Override Classification" button). Separate from the Phase 6
     * manual-verify override
     * ({@link #verify(UserPrincipal, Long, VerificationDecisionRequest)}):
     * that one is the Verification Team's stand-in for the (not-yet-real)
     * AI confidence check and only legal at AI_PROCESSING; this one is
     * the Officer/Department Head's post-assignment correction of a
     * category and/or severity that turns out to be wrong, legal from
     * VERIFIED through IN_PROGRESS. See
     * ComplaintService.overrideClassification's Javadoc.
     */
    @PatchMapping("/{id}/classification")
    @PreAuthorize("hasAnyRole('GOVERNMENT_OFFICER', 'DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public ComplaintResponse overrideClassification(@AuthenticationPrincipal UserPrincipal principal,
                                                      @PathVariable Long id,
                                                      @Valid @RequestBody ClassificationOverrideRequest request) {
        return complaintService.overrideClassification(principal.getUser(), id, request);
    }

    /**
     * Phase 12 (SRS 16.2 Officer Queue "Escalate" button). Manual,
     * officer/department-head-initiated counterpart to Phase 11's
     * automatic {@code EscalationSchedulerService} SLA-breach sweep -
     * same is_escalated/escalated_at annotation semantics, just triggered
     * by a human instead of the scheduler. See
     * ComplaintService.escalate's Javadoc.
     */
    @PatchMapping("/{id}/escalate")
    @PreAuthorize("hasAnyRole('GOVERNMENT_OFFICER', 'DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public ComplaintResponse escalate(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return complaintService.escalate(principal.getUser(), id);
    }

    /**
     * Phase 12 (SRS 16.2 Complaint Detail (Officer View) "Add Internal
     * Note" button/field). Staff-only annotation on a complaint, not
     * visible to the citizen - see ComplaintService.addInternalNote's
     * Javadoc for why this is an AuditLog entry rather than a new table.
     */
    @PostMapping("/{id}/notes")
    @PreAuthorize("hasAnyRole('GOVERNMENT_OFFICER', 'DEPARTMENT_HEAD', 'VERIFICATION_TEAM', 'ADMIN', 'SUPER_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ComplaintResponse addInternalNote(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable Long id,
                                              @Valid @RequestBody InternalNoteRequest request) {
        return complaintService.addInternalNote(principal.getUser(), id, request);
    }

    /**
     * Phase 11 (SRS 15.7 Features: "manual reassignment by Admin or
     * Department Head") - see AssignmentRequest's Javadoc and
     * ComplaintService.reassign for why this is separate from
     * PATCH .../status.
     */
    @PatchMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public ComplaintResponse reassign(@AuthenticationPrincipal UserPrincipal principal,
                                       @PathVariable Long id,
                                       @Valid @RequestBody AssignmentRequest request) {
        return complaintService.reassign(principal.getUser(), id, request);
    }

    /** Phase 11 (SRS 15.9 budget-approval-threshold gate) - see ComplaintService.approveBudget's Javadoc. */
    @PatchMapping("/{id}/approve-budget")
    @PreAuthorize("hasAnyRole('DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public ComplaintResponse approveBudget(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return complaintService.approveBudget(principal.getUser(), id);
    }

    /** Gap-backlog Patch 41 (Sep 2026 strict recheck): citizen confirms a RESOLVED complaint is fixed -> CLOSED. */
    @PostMapping("/{id}/confirm-resolution")
    @PreAuthorize("hasRole('CITIZEN')")
    public ComplaintResponse confirmResolution(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return complaintService.confirmResolution(principal.getUser(), id);
    }

    /** Gap-backlog Patch 15 (Sep 2026 strict recheck): explicit budget rejection, same roles/scope as approve-budget. */
    @PatchMapping("/{id}/reject-budget")
    @PreAuthorize("hasAnyRole('DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public ComplaintResponse rejectBudget(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id,
                                          @Valid @RequestBody(required = false) BudgetRejectionRequest request) {
        return complaintService.rejectBudget(principal.getUser(), id, request != null ? request.note() : null);
    }

    // ---- Gap-backlog Patch 11 (Sep 2026 audit): citizen resolution rating ----

    @PostMapping("/{id}/rating")
    @PreAuthorize("hasRole('CITIZEN')")
    @ResponseStatus(HttpStatus.CREATED)
    public RatingResponse rate(@AuthenticationPrincipal UserPrincipal principal,
                                @PathVariable Long id,
                                @Valid @RequestBody RatingRequest request) {
        return ratingService.rate(principal.getUser(), id, request);
    }

    @GetMapping("/{id}/rating")
    @PreAuthorize("hasRole('CITIZEN')")
    public RatingResponse getRating(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return ratingService.getRating(principal.getUser(), id);
    }

    // ---- Gap-backlog Patch 12 (Sep 2026 audit): appeal after rejection ----

    @PostMapping("/{id}/appeal")
    @PreAuthorize("hasRole('CITIZEN')")
    @ResponseStatus(HttpStatus.CREATED)
    public AppealResponse appeal(@AuthenticationPrincipal UserPrincipal principal,
                                  @PathVariable Long id,
                                  @Valid @RequestBody AppealRequest request) {
        return appealService.submit(principal.getUser(), id, request);
    }

    @GetMapping("/{id}/appeal")
    @PreAuthorize("isAuthenticated()")
    public List<AppealResponse> listAppeals(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return appealService.listForComplaint(principal.getUser(), id);
    }

    /** Staff review queue - same role set as {@link #verify}, the closest analogous decision-making action. */
    @GetMapping("/appeals/pending")
    @PreAuthorize("hasAnyRole('VERIFICATION_TEAM', 'DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public List<AppealResponse> pendingAppeals(@AuthenticationPrincipal UserPrincipal principal) {
        return appealService.listPending(principal.getUser()); // audit GAP-030: DH sees own department only
    }

    @PatchMapping("/appeals/{appealId}/review")
    @PreAuthorize("hasAnyRole('VERIFICATION_TEAM', 'DEPARTMENT_HEAD', 'ADMIN', 'SUPER_ADMIN')")
    public AppealResponse reviewAppeal(@AuthenticationPrincipal UserPrincipal principal,
                                        @PathVariable Long appealId,
                                        @Valid @RequestBody AppealReviewRequest request) {
        return appealService.review(principal.getUser(), appealId, request);
    }
}
