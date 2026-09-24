package com.jannetai.backend.service.complaint;

import com.jannetai.backend.dto.complaint.AiClassificationResponse;
import com.jannetai.backend.dto.complaint.AssignmentRequest;
import com.jannetai.backend.dto.complaint.BudgetResponse;
import com.jannetai.backend.dto.complaint.ClassificationOverrideRequest;
import com.jannetai.backend.dto.complaint.ComplaintResponse;
import com.jannetai.backend.dto.complaint.ComplaintSummaryResponse;
import com.jannetai.backend.dto.complaint.ImageResponse;
import com.jannetai.backend.dto.complaint.InternalNoteRequest;
import com.jannetai.backend.dto.complaint.InternalNoteResponse;
import com.jannetai.backend.dto.complaint.StatusHistoryResponse;
import com.jannetai.backend.dto.complaint.StatusUpdateRequest;
import com.jannetai.backend.dto.complaint.VerificationDecisionRequest;
import com.jannetai.backend.entity.Budget;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.Image;
import com.jannetai.backend.entity.Location;
import com.jannetai.backend.entity.StatusHistory;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.BudgetApprovalStatus;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.LocationSource;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.Severity;
import com.jannetai.backend.entity.enums.VerificationDecision;
import com.jannetai.backend.exception.BudgetApprovalRequiredException;
import com.jannetai.backend.exception.ComplaintLimitExceededException;
import com.jannetai.backend.exception.GracePeriodExpiredException;
import com.jannetai.backend.exception.InvalidStateTransitionException;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.exception.UnverifiedIdentifierException;
import com.jannetai.backend.repository.AuditLogRepository;
import com.jannetai.backend.repository.BudgetRepository;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.ImageRepository;
import com.jannetai.backend.repository.PredictionRepository;
import com.jannetai.backend.repository.StatusHistoryRepository;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.admin.PlatformSettingKey;
import com.jannetai.backend.service.admin.PlatformSettingsService;
import com.jannetai.backend.service.notification.NotificationService;
import com.jannetai.backend.storage.ImageValidationService;
import com.jannetai.backend.storage.StorageService;
import com.jannetai.backend.storage.StoredObject;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;
import java.util.Set;

/**
 * Core Complaint Module service (SRS 15.3). Owns creation, detail/list
 * retrieval, the manual Verification Team override approved for the
 * pre-AI-service gap (Phase 6 instruction; SRS 13.6), citizen reopen, and
 * the generic downstream status-update action - every status write goes
 * through {@link ComplaintStateMachine} first, per ARCHITECTURE.md Section
 * 4 ("arbitrary transitions are not permitted").
 */
@Service
@RequiredArgsConstructor
public class ComplaintService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_PHOTO_BYTES = 10L * 1024 * 1024; // SRS 15.1/17.2: 10 MB
    private static final int MAX_DESCRIPTION_LENGTH = 500; // SRS 15.1/17.2

    private final PriorityBudgetPredictionService priorityBudgetPredictionService;
    private final DepartmentAssignmentService departmentAssignmentService;
    private final ComplaintRepository complaintRepository;
    private final ImageRepository imageRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final AuditLogRepository auditLogRepository;
    private final BudgetRepository budgetRepository;
    private final PredictionRepository predictionRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final LocationService locationService;
    private final StorageService storageService;
    private final ImageValidationService imageValidationService;
    private final AuditService auditService;
    private final PlatformSettingsService platformSettingsService;
    private final NotificationService notificationService; // Phase 15: citizen/officer alerts, see recordHistory/reassign

    @Value("${app.complaint.max-submissions-per-24h}")
    private int maxSubmissionsPer24h;

    @Value("${app.complaint.reopen-grace-period-days}")
    private int reopenGracePeriodDays;

    /**
     * SRS 15.9 Business Rules: "estimates above a configurable threshold
     * require Department Head approval before the complaint can move to
     * In Progress." No concrete number is given in the SRS (same
     * documented-placeholder situation as {@code reopenGracePeriodDays}
     * above) - see PROJECT_INTEGRATION.md Section 6 for the chosen
     * default and reasoning. Phase 14 (Admin & Settings Module) now lets
     * an Admin override this via {@link PlatformSettingsService} - see
     * {@link #requireBudgetApprovedIfNeeded}; this field remains the
     * fallback default until an Admin actually sets an override.
     */
    @Value("${app.budget.approval-threshold-inr}")
    private BigDecimal budgetApprovalThresholdInr;

    // ---- Create ----

    /**
     * Creates a new complaint. Per SRS Table 16 ("Draft") this backend
     * never persists a DRAFT row - Draft is described as a client-side,
     * pre-upload-completion concept ("record created client-side while the
     * photo/media is still uploading... auto-expires... if upload does not
     * complete within 15 minutes"); Flutter never has a reason to call this
     * API before it already has the photo bytes ready to send in the same
     * multipart request, so there is no server-observable Draft state to
     * model. The complaint is created directly at SUBMITTED and then,
     * synchronously in the same transaction, system-advanced to
     * AI_PROCESSING (see class Javadoc and PROJECT_INTEGRATION.md Section 6
     * for the full reasoning) - the persisted/returned status is
     * AI_PROCESSING, not SUBMITTED, a deliberate, documented deviation from
     * the SRS API table's literal example response.
     */
    @Transactional
    public ComplaintResponse create(User citizen, MultipartFile photo, String description,
                                     BigDecimal latitude, BigDecimal longitude, Long wardId,
                                     LocationSource source) {
        requireVerifiedIdentifier(citizen);
        requireWithinSubmissionLimit(citizen);
        validatePhoto(photo);
        // Gap-backlog Patch 21/49 (Sep 2026 audit): real-bytes validation
        // (magic bytes, decodability, dimension bounds) beyond the
        // declared Content-Type header validatePhoto just checked, plus
        // EXIF/metadata stripping for JPEG/PNG - see
        // ImageValidationService's Javadoc. The sanitized replacement is
        // what actually gets stored below, never the raw upload.
        photo = imageValidationService.validateAndSanitize(photo);
        validateDescription(description);
        // Remaining-gaps item 3: coordinates may be omitted entirely when GPS is
        // unavailable, provided a ward is chosen (server-side ward fallback).
        Location location;
        if (latitude == null && longitude == null) {
            if (wardId == null) {
                throw new IllegalArgumentException(
                        "A location is required: send latitude and longitude, or choose your ward if GPS is unavailable");
            }
            location = locationService.resolveWardFallbackAndSave(wardId);
        } else {
            if (latitude == null || longitude == null) {
                throw new IllegalArgumentException("latitude and longitude must be provided together");
            }
            if (source == LocationSource.WARD_FALLBACK) {
                throw new IllegalArgumentException("WARD_FALLBACK is assigned by the server and cannot be sent with coordinates");
            }
            requireInRange(latitude, BigDecimal.valueOf(-90), BigDecimal.valueOf(90), "latitude");
            requireInRange(longitude, BigDecimal.valueOf(-180), BigDecimal.valueOf(180), "longitude");
            location = locationService.resolveAndSave(latitude, longitude, wardId, source, null);
        }

        Complaint complaint = Complaint.builder()
                .referenceNumber("PENDING") // placeholder, overwritten below once the ID exists
                .citizen(citizen)
                .category(ComplaintCategory.GENERAL) // AI Analysis Module (Phase 7) normally sets this; GENERAL is the documented unmapped-category fallback (SRS 15.7) until a Verification Team override or the real AI service assigns a real one
                .description(description)
                .location(location)
                .status(ComplaintStatus.SUBMITTED)
                .corroborationCount(1)
                .isEscalated(false)
                .isReopened(false)
                .build();
        complaint = complaintRepository.save(complaint);

        complaint.setReferenceNumber(generateReferenceNumber(complaint.getComplaintId()));
        complaint = complaintRepository.save(complaint);

        recordHistory(complaint, null, ComplaintStatus.SUBMITTED, citizen,
                ComplaintStateMachine.actorTypeFor(citizen.getRole()), "Complaint submitted by citizen");

        // System-initiated, immediate: queue for AI processing. No real AI
        // Analysis Module exists yet (Phase 7) - the complaint is
        // deliberately left parked here; see class Javadoc.
        ComplaintStateMachine.assertSystemTransitionAllowed(ComplaintStatus.SUBMITTED, ComplaintStatus.AI_PROCESSING);
        complaint.setStatus(ComplaintStatus.AI_PROCESSING);
        complaint = complaintRepository.save(complaint);
        recordHistory(complaint, ComplaintStatus.SUBMITTED, ComplaintStatus.AI_PROCESSING, null,
                com.jannetai.backend.entity.enums.ActorType.SYSTEM,
                "Queued for AI processing (AI Analysis Module not yet implemented - Phase 7; "
                        + "held pending Verification Team manual review per the approved Phase 6 override)");

        StoredObject stored = storageService.store(photo, "complaints/" + complaint.getComplaintId());
        Image image = Image.builder()
                .complaint(complaint)
                .imageType(com.jannetai.backend.entity.enums.ImageType.BEFORE)
                .storageKey(stored.storageKey())
                .contentType(stored.contentType())
                .fileSizeBytes((int) stored.fileSizeBytes())
                .uploadedBy(citizen)
                .build();
        imageRepository.save(image);

        auditService.record(citizen, "COMPLAINT_SUBMITTED", "COMPLAINT", complaint.getComplaintId(),
                "{\"referenceNumber\":\"" + complaint.getReferenceNumber() + "\"}");

        return toResponse(complaint);
    }

    // ---- Read ----

    @Transactional(readOnly = true)
    public ComplaintResponse getDetail(User requester, Long complaintId) {
        Complaint complaint = requireComplaint(complaintId);
        requireCanView(requester, complaint);
        // Phase 12: internal notes (SRS 16.2) are staff-only - a citizen
        // viewing their own complaint never sees them.
        return toResponse(complaint, requester.getRole() != Role.CITIZEN);
    }

    /**
     * Phase 12 (Officer Module, SRS 16.2 Officer Queue Permissions): a
     * GOVERNMENT_OFFICER always sees only complaints assigned directly to
     * them, and a DEPARTMENT_HEAD always sees their whole department's
     * queue - both are now default-restricted server-side rather than
     * only optionally narrowable via the {@code departmentId} query
     * param, closing the gap PROJECT_INTEGRATION.md Section 6 ("Phase 6 -
     * Staff visibility... not department-scoped", PARTIALLY SUPERSEDED
     * Phase 11 note) left open for "whichever phase builds the actual
     * officer/department-head-facing queue UI". Any caller-supplied
     * {@code departmentId} is ignored for these two roles - it cannot be
     * used to widen scope beyond their own department, and for a
     * GOVERNMENT_OFFICER it would be redundant with their own department
     * anyway. VERIFICATION_TEAM/ADMIN/SUPER_ADMIN/MAINTENANCE_TEAM keep
     * the unrestricted (optionally self-filtered) Phase 6 behavior - none
     * of those roles has an SRS-documented "own queue" concept.
     */
    @Transactional(readOnly = true)
    public Page<ComplaintSummaryResponse> list(User requester, ComplaintStatus status,
                                                ComplaintCategory category, Long departmentId,
                                                int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(pageSize, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Complaint> results;
        if (requester.getRole() == Role.CITIZEN) {
            results = complaintRepository.findForCitizen(requester.getUserId(), status, category, pageable);
        } else if (requester.getRole() == Role.GOVERNMENT_OFFICER) {
            results = complaintRepository.findForOfficerOrDepartment(
                    requireOwnDepartmentId(requester), requester.getUserId(), status, category, pageable);
        } else if (requester.getRole() == Role.DEPARTMENT_HEAD) {
            results = complaintRepository.findForOfficerOrDepartment(
                    requireOwnDepartmentId(requester), null, status, category, pageable);
        } else {
            results = complaintRepository.findForStaff(status, category, departmentId, pageable);
        }

        return results.map(ComplaintSummaryResponse::from);
    }

    /**
     * A GOVERNMENT_OFFICER/DEPARTMENT_HEAD account with no department
     * assigned has an empty queue by definition (there is nothing to
     * scope to) rather than an error - account provisioning without a
     * department is an Admin Module (Phase 14) data-entry concern, not
     * something the Officer Module should reject at query time.
     */
    private Long requireOwnDepartmentId(User staff) {
        return staff.getDepartment() != null ? staff.getDepartment().getDepartmentId() : -1L;
    }

    // ---- Manual Verification Team override ----

    /**
     * The approved manual override for the pre-AI-service gap: lets
     * VERIFICATION_TEAM (or ADMIN/SUPER_ADMIN) make the same VERIFIED /
     * REJECTED / DUPLICATE call the AI Analysis Module's confidence check
     * would otherwise make (SRS Table 10, 15.4). Only legal while the
     * complaint is AI_PROCESSING - this is deliberately narrower than every
     * role ComplaintStateMachine's ACTOR_ROLES table permits out of
     * VERIFIED/ASSIGNED (those are structural allowances for Phase 11+, not
     * something this endpoint exposes).
     */
    @Transactional
    public ComplaintResponse verify(User staff, Long complaintId, VerificationDecisionRequest request) {
        Complaint complaint = requireComplaint(complaintId);
        if (complaint.getStatus() != ComplaintStatus.AI_PROCESSING) {
            throw new InvalidStateTransitionException(
                    "Manual verification is only available while a complaint is AI_PROCESSING (current status: "
                            + complaint.getStatus() + ")");
        }

        ComplaintStatus target = switch (request.decision()) {
            case VERIFIED -> ComplaintStatus.VERIFIED;
            case REJECTED -> ComplaintStatus.REJECTED;
            case DUPLICATE -> ComplaintStatus.DUPLICATE;
        };
        ComplaintStateMachine.assertTransitionAllowed(complaint.getStatus(), target, staff.getRole());

        String reason;
        switch (request.decision()) {
            case VERIFIED -> {
                if (request.category() == null) {
                    throw new InvalidStateTransitionException("category is required for a VERIFIED decision");
                }
                complaint.setCategory(request.category());
                complaint.setSeverity(request.severity());
                reason = requireNonBlankOr(request.note(), "Manually verified by Verification Team");
            }
            case REJECTED -> {
                if (isBlank(request.rejectionReasonCode())) {
                    throw new InvalidStateTransitionException("rejectionReasonCode is required for a REJECTED decision");
                }
                complaint.setRejectionReasonCode(request.rejectionReasonCode());
                reason = requireNonBlankOr(request.note(), "Rejected: " + request.rejectionReasonCode());
            }
            case DUPLICATE -> {
                if (request.parentComplaintId() == null) {
                    throw new InvalidStateTransitionException("parentComplaintId is required for a DUPLICATE decision");
                }
                if (request.parentComplaintId().equals(complaintId)) {
                    throw new InvalidStateTransitionException("A complaint cannot be marked a duplicate of itself");
                }
                Complaint parent = requireComplaint(request.parentComplaintId());
                parent.setCorroborationCount(parent.getCorroborationCount() + 1);
                complaintRepository.save(parent);
                complaint.setParentComplaint(parent);
                reason = requireNonBlankOr(request.note(),
                        "Merged as duplicate of " + parent.getReferenceNumber());
            }
            default -> throw new InvalidStateTransitionException("Unknown verification decision");
        }

        ComplaintStatus previous = complaint.getStatus();
        complaint.setStatus(target);
        complaint = complaintRepository.save(complaint);
        recordHistory(complaint, previous, target, staff, ComplaintStateMachine.actorTypeFor(staff.getRole()), reason);

        auditService.record(staff, "COMPLAINT_" + request.decision().name(), "COMPLAINT",
                complaint.getComplaintId(), "{\"reason\":\"" + escapeJson(reason) + "\"}");

        // Phase 10 (SRS 15.8/15.9): runs for the VERIFIED decision only -
        // a manually REJECTED/DUPLICATE complaint has nothing to score.
        // request.severity() was already applied directly above (staff
        // override always wins, unconditionally, even if ai-service is
        // down - see PriorityBudgetPredictionService's Javadoc "SEVERITY
        // OVERRIDE PRECEDENCE"); this call additionally computes the
        // numeric priority score and budget estimate for audit/planning
        // purposes and never re-overwrites a staff-supplied severity.
        if (request.decision() == VerificationDecision.VERIFIED) {
            priorityBudgetPredictionService.predictAndApply(complaint, request.severity());

            // Phase 11 (SRS 15.7): runs immediately after prediction on the
            // manual-verify path too, exactly mirroring
            // AiClassificationService.applyAutoVerification's wiring -
            // see DepartmentAssignmentService's class Javadoc "WIRING
            // POINT". Transitions VERIFIED -> ASSIGNED.
            departmentAssignmentService.assignAndApply(complaint);
        }

        return toResponse(complaint, true);
    }

    // ---- Citizen reopen ----

    @Transactional
    public ComplaintResponse reopen(User citizen, Long complaintId) {
        return reopen(citizen, complaintId, null);
    }

    /** Gap-backlog Patch 41: reopen/dispute with the citizen's optional reason recorded in status history. */
    @Transactional
    public ComplaintResponse reopen(User citizen, Long complaintId, String reason) {
        Complaint complaint = requireComplaint(complaintId);
        if (!complaint.getCitizen().getUserId().equals(citizen.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may only reopen your own complaints");
        }
        if (complaint.getStatus() != ComplaintStatus.RESOLVED && complaint.getStatus() != ComplaintStatus.CLOSED) {
            throw new InvalidStateTransitionException(
                    "Only a Resolved or Closed complaint can be reopened (current status: "
                            + complaint.getStatus() + ")");
        }
        LocalDateTime graceDeadline = complaint.getUpdatedAt().plusDays(reopenGracePeriodDays);
        if (LocalDateTime.now().isAfter(graceDeadline)) {
            throw new GracePeriodExpiredException(
                    "The " + reopenGracePeriodDays + "-day reopen grace period has expired for this complaint");
        }

        ComplaintStatus previous = complaint.getStatus();
        ComplaintStateMachine.assertTransitionAllowed(previous, ComplaintStatus.IN_PROGRESS, citizen.getRole());

        complaint.setStatus(ComplaintStatus.IN_PROGRESS);
        complaint.setIsReopened(true);
        complaint.setReopenedAt(LocalDateTime.now());
        complaint = complaintRepository.save(complaint);

        recordHistory(complaint, previous, ComplaintStatus.IN_PROGRESS, citizen,
                ComplaintStateMachine.actorTypeFor(citizen.getRole()),
                reason == null || reason.isBlank()
                        ? "Citizen reopened complaint"
                        : "Citizen reopened complaint: " + reason.strip());

        auditService.record(citizen, "COMPLAINT_REOPENED", "COMPLAINT", complaint.getComplaintId(), null);

        return toResponse(complaint);
    }

    /**
     * Gap-backlog Patch 41 (Sep 2026 strict recheck): citizen "Confirm
     * Resolution". ComplaintStateMachine has always allowed CITIZEN to move
     * RESOLVED -> CLOSED ("citizen confirms" - see its own comment), but no
     * endpoint exposed that transition, so a citizen could only reopen,
     * never confirm. Goes through the same assertTransitionAllowed /
     * recordHistory / audit path as every other transition.
     */
    @Transactional
    public ComplaintResponse confirmResolution(User citizen, Long complaintId) {
        Complaint complaint = requireComplaint(complaintId);
        if (!complaint.getCitizen().getUserId().equals(citizen.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may only confirm your own complaints");
        }
        if (complaint.getStatus() != ComplaintStatus.RESOLVED) {
            throw new InvalidStateTransitionException(
                    "Only a Resolved complaint can be confirmed (current status: " + complaint.getStatus() + ")");
        }
        ComplaintStatus previous = complaint.getStatus();
        ComplaintStateMachine.assertTransitionAllowed(previous, ComplaintStatus.CLOSED, citizen.getRole());

        complaint.setStatus(ComplaintStatus.CLOSED);
        complaint = complaintRepository.save(complaint);

        recordHistory(complaint, previous, ComplaintStatus.CLOSED, citizen,
                ComplaintStateMachine.actorTypeFor(citizen.getRole()), "Citizen confirmed the resolution");
        auditService.record(citizen, "COMPLAINT_RESOLUTION_CONFIRMED", "COMPLAINT", complaint.getComplaintId(), null);

        return toResponse(complaint);
    }

    // ---- Generic downstream status update (Officer/DeptHead/Admin) ----

    /**
     * SRS Table 23's PATCH .../status + Table 8's "Officer Status Update
     * Form" (17.3) conditional field validation. Structurally validated
     * and role-gated against {@link ComplaintStateMachine} the same as
     * every other transition. Phase 12 (Officer Module) changes from the
     * Phase 6 version:
     * <ul>
     *   <li>Now reachable past VERIFIED for real - Department Assignment
     *       (Phase 11) sets department_id, so ASSIGNED/IN_PROGRESS/
     *       RESOLVED are genuinely exercised by officers now, not just
     *       structurally validated.</li>
     *   <li>Scope-checked via {@link #requireCanView} - a
     *       GOVERNMENT_OFFICER may only update a complaint assigned
     *       directly to them (SRS Table 8: "must exist and be assigned to
     *       acting officer"); a DEPARTMENT_HEAD only within their own
     *       department.</li>
     *   <li>{@code note} is now mandatory (min 10 chars) when
     *       {@code target} is RESOLVED or REJECTED, and {@code afterPhoto}
     *       is mandatory when {@code target} is RESOLVED - both per SRS
     *       Table 8's "Conditional (mandatory for Resolved/Rejected)" /
     *       "Conditional (mandatory for Resolved)" validation, closing
     *       the KNOWN LIMITATION StatusUpdateRequest's Phase 6 Javadoc
     *       flagged.</li>
     * </ul>
     */
    @Transactional
    public ComplaintResponse updateStatus(User staff, Long complaintId, StatusUpdateRequest request,
                                           MultipartFile afterPhoto) {
        Complaint complaint = requireComplaint(complaintId);
        requireCanView(staff, complaint);
        ComplaintStatus previous = complaint.getStatus();
        ComplaintStatus target = request.newStatus();
        if (target == null) {
            throw new IllegalArgumentException("newStatus is required");
        }

        if (target == ComplaintStatus.ASSIGNED && complaint.getDepartment() == null) {
            throw new InvalidStateTransitionException(
                    "Cannot move to ASSIGNED without a department - this should only be reachable through "
                            + "the Department Assignment Module, never directly via this endpoint");
        }

        // SRS Table 8: officer_note mandatory (min 10 chars) for
        // Resolved/Rejected targets.
        if ((target == ComplaintStatus.RESOLVED || target == ComplaintStatus.REJECTED)
                && (isBlank(request.note()) || request.note().trim().length() < 10)) {
            throw new IllegalArgumentException(
                    "note is required (minimum 10 characters) when moving a complaint to " + target);
        }
        // SRS Table 8: after_photo mandatory for a Resolved target.
        if (target == ComplaintStatus.RESOLVED) {
            validatePhoto(afterPhoto);
            afterPhoto = imageValidationService.validateAndSanitize(afterPhoto);
        } else if (afterPhoto != null && !afterPhoto.isEmpty()) {
            // Accepted but not required for any other target (SRS Table 8
            // marks it conditional only for Resolved) - still validated
            // the same way as a Resolved photo so a malformed upload
            // never gets silently stored.
            validatePhoto(afterPhoto);
            afterPhoto = imageValidationService.validateAndSanitize(afterPhoto);
        }

        // Phase 11 (SRS 15.9 budget-approval-threshold gate): an
        // ASSIGNED -> IN_PROGRESS move is blocked while the complaint's
        // budget estimate exceeds the configured threshold and no
        // Department Head has approved it yet. See
        // BudgetApprovalRequiredException's Javadoc and
        // ComplaintService#approveBudget for the resolution path.
        if (previous == ComplaintStatus.ASSIGNED && target == ComplaintStatus.IN_PROGRESS) {
            requireBudgetApprovedIfNeeded(complaint);
        }

        ComplaintStateMachine.assertTransitionAllowed(previous, target, staff.getRole());

        complaint.setStatus(target);
        complaint = complaintRepository.save(complaint);
        recordHistory(complaint, previous, target, staff, ComplaintStateMachine.actorTypeFor(staff.getRole()),
                requireNonBlankOr(request.note(), "Status updated"));

        if (target == ComplaintStatus.RESOLVED && afterPhoto != null && !afterPhoto.isEmpty()) {
            StoredObject stored = storageService.store(afterPhoto, "complaints/" + complaint.getComplaintId());
            Image image = Image.builder()
                    .complaint(complaint)
                    .imageType(com.jannetai.backend.entity.enums.ImageType.AFTER)
                    .storageKey(stored.storageKey())
                    .contentType(stored.contentType())
                    .fileSizeBytes((int) stored.fileSizeBytes())
                    .uploadedBy(staff)
                    .build();
            imageRepository.save(image);
        }

        auditService.record(staff, "COMPLAINT_STATUS_UPDATED", "COMPLAINT", complaint.getComplaintId(),
                "{\"from\":\"" + previous + "\",\"to\":\"" + target + "\"}");

        return toResponse(complaint, true);
    }

    // ---- Officer/Department Head classification override (SRS 15.8) ----

    /**
     * PATCH .../complaints/{id}/classification - Phase 12 (SRS 15.8
     * Features: "manual override by Officer/Department Head with
     * justification"; SRS 16.2 "Override Classification"). Deliberately
     * separate from {@link #verify}: verify is the Verification Team's
     * one-time stand-in for the (not-yet-real) AI confidence check, legal
     * only at AI_PROCESSING; this is the assigned Officer/Department
     * Head's post-assignment correction once they've actually looked at
     * the issue in the field, legal from VERIFIED through IN_PROGRESS.
     * Does not itself change {@code status} (an AuditLog entry is written
     * instead of a StatusHistory row, same non-status-change precedent as
     * Phase 11's escalation events) and does not re-run priority/budget
     * prediction - a category/severity correction after assignment is a
     * documented, deliberately out-of-scope trigger for re-prediction
     * this phase; see PROJECT_INTEGRATION.md Section 6.
     */
    @Transactional
    public ComplaintResponse overrideClassification(User staff, Long complaintId,
                                                      ClassificationOverrideRequest request) {
        Complaint complaint = requireComplaint(complaintId);
        requireCanView(staff, complaint);

        if (request.category() == null && request.severity() == null) {
            throw new IllegalArgumentException(
                    "At least one of category or severity must be supplied to override classification");
        }
        Set<ComplaintStatus> overridable = Set.of(
                ComplaintStatus.VERIFIED, ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS);
        if (!overridable.contains(complaint.getStatus())) {
            throw new InvalidStateTransitionException(
                    "Classification can only be overridden while a complaint is VERIFIED, ASSIGNED, or "
                            + "IN_PROGRESS (current status: " + complaint.getStatus() + ")");
        }

        ComplaintCategory previousCategory = complaint.getCategory();
        Severity previousSeverity = complaint.getSeverity();
        if (request.category() != null) {
            complaint.setCategory(request.category());
        }
        if (request.severity() != null) {
            complaint.setSeverity(request.severity());
        }
        complaint = complaintRepository.save(complaint);

        auditService.record(staff, "COMPLAINT_CLASSIFICATION_OVERRIDDEN", "COMPLAINT", complaint.getComplaintId(),
                "{\"previous_category\":\"" + previousCategory + "\",\"new_category\":\"" + complaint.getCategory()
                        + "\",\"previous_severity\":\"" + previousSeverity + "\",\"new_severity\":\""
                        + complaint.getSeverity() + "\",\"reason\":\"" + escapeJson(request.reason()) + "\"}");

        return toResponse(complaint, true);
    }

    // ---- Manual escalation (SRS 16.2 "Escalate" button) ----

    /**
     * PATCH .../complaints/{id}/escalate - Phase 12's human-initiated
     * counterpart to {@link com.jannetai.backend.service.department.EscalationSchedulerService}'s
     * automatic SLA-breach sweep. Same annotation semantics (sets
     * is_escalated/escalated_at, never touches status - see that class's
     * Javadoc for the full "ESCALATED is an annotation, not a status"
     * reasoning) and the same AuditLog-not-StatusHistory choice. Calling
     * this on an already-escalated complaint is a harmless no-op (SRS
     * doesn't describe a "de-escalate" or "re-escalate" concept).
     */
    @Transactional
    public ComplaintResponse escalate(User staff, Long complaintId) {
        Complaint complaint = requireComplaint(complaintId);
        requireCanView(staff, complaint);

        if (!Boolean.TRUE.equals(complaint.getIsEscalated())) {
            complaint.setIsEscalated(true);
            complaint.setEscalatedAt(LocalDateTime.now());
            complaint = complaintRepository.save(complaint);

            auditService.record(staff, "COMPLAINT_ESCALATED_MANUAL", "COMPLAINT", complaint.getComplaintId(),
                    "{\"status\":\"" + complaint.getStatus() + "\"}");
        }

        return toResponse(complaint, true);
    }

    // ---- Internal notes (SRS 16.2 "Add Internal Note") ----

    /**
     * POST .../complaints/{id}/notes - a staff-only annotation on a
     * complaint (SRS 16.2 Complaint Detail (Officer View)), never visible
     * to the citizen. Stored as an AuditLog row (see AuditLogRepository's
     * Phase 12 Javadoc for why this doesn't get a dedicated table) with
     * the note text itself as the {@code details} JSON payload.
     */
    @Transactional
    public ComplaintResponse addInternalNote(User staff, Long complaintId, InternalNoteRequest request) {
        Complaint complaint = requireComplaint(complaintId);
        requireCanView(staff, complaint);

        auditService.record(staff, "COMPLAINT_INTERNAL_NOTE_ADDED", "COMPLAINT", complaint.getComplaintId(),
                "{\"note\":\"" + escapeJson(request.note()) + "\"}");

        return toResponse(complaint, true);
    }

    // ---- Budget approval gate (SRS 15.9) ----

    private void requireBudgetApprovedIfNeeded(Complaint complaint) {
        Budget budget = budgetRepository
                .findFirstByComplaint_ComplaintIdOrderByCreatedAtDescBudgetIdDesc(complaint.getComplaintId())
                .orElse(null);
        // No Budget row at all (e.g. budget-predict failed for this
        // complaint - Phase 10's own documented KNOWN LIMITATION) means
        // there's no estimate to gate on; the transition proceeds. The
        // gate only ever blocks a complaint that DOES have an estimate
        // exceeding the threshold.
        if (budget == null) {
            return;
        }
        BigDecimal threshold = effectiveBudgetApprovalThresholdInr();
        boolean exceedsThreshold = budget.getEstimatedCostMax() != null
                && budget.getEstimatedCostMax().compareTo(threshold) > 0;
        if (exceedsThreshold && budget.getApprovedBy() == null) {
            throw new BudgetApprovalRequiredException(
                    "This complaint's estimated cost (up to " + budget.getEstimatedCostMax()
                            + " INR) exceeds the " + threshold
                            + " INR approval threshold and requires Department Head approval "
                            + "before moving to IN_PROGRESS - see PATCH .../complaints/" + complaint.getComplaintId()
                            + "/approve-budget");
        }
    }

    /** Phase 14: Admin-set PLATFORM setting override (SRS 15.15), falling back to the app.budget.approval-threshold-inr @Value default. */
    private BigDecimal effectiveBudgetApprovalThresholdInr() {
        return platformSettingsService.getOverride(PlatformSettingKey.BUDGET_APPROVAL_THRESHOLD_INR)
                .map(BigDecimal::new).orElse(budgetApprovalThresholdInr);
    }

    /**
     * SRS 15.9: Department Head (or Admin/Super Admin) approval for a
     * budget estimate above the configurable threshold, unblocking the
     * ASSIGNED -&gt; IN_PROGRESS transition {@link #updateStatus} would
     * otherwise refuse. A no-op precondition failure (already approved,
     * or the estimate never exceeded the threshold) is not treated as an
     * error - approving an already-approved or never-gated budget is
     * harmless and idempotent.
     */
    @Transactional
    public ComplaintResponse approveBudget(User staff, Long complaintId) {
        Complaint complaint = requireComplaint(complaintId);
        // Phase 13 (Department Head Module) fix: this call was missing a
        // scope check entirely since Phase 11 - a DEPARTMENT_HEAD could
        // approve the budget of a complaint in *any* department, not just
        // their own, purely by guessing/incrementing a complaint_id. See
        // requireCanView's Javadoc; a no-op for ADMIN/SUPER_ADMIN, the
        // only other roles @PreAuthorize allows onto this endpoint.
        requireCanView(staff, complaint);
        Budget budget = budgetRepository
                .findFirstByComplaint_ComplaintIdOrderByCreatedAtDescBudgetIdDesc(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No budget estimate exists yet for complaint " + complaintId));

        budget.setApprovedBy(staff);
        budget.setApprovalStatus(BudgetApprovalStatus.APPROVED);
        budget.setApprovedAt(LocalDateTime.now());
        budgetRepository.save(budget);

        auditService.record(staff, "BUDGET_APPROVED", "COMPLAINT", complaintId,
                "{\"estimatedCostMax\":\"" + budget.getEstimatedCostMax() + "\"}");

        return toResponse(complaint, true);
    }

    /**
     * Gap-backlog Patch 15 (Sep 2026 strict recheck): explicit budget
     * rejection. Same scope check as approveBudget. A rejected estimate keeps
     * requireBudgetApprovedIfNeeded blocking progress (approvedBy is cleared),
     * so the work cannot proceed on a rejected budget; the decision, the
     * actor and the optional note are recorded in the audit log.
     */
    @Transactional
    public ComplaintResponse rejectBudget(User staff, Long complaintId, String note) {
        Complaint complaint = requireComplaint(complaintId);
        requireCanView(staff, complaint);
        Budget budget = budgetRepository
                .findFirstByComplaint_ComplaintIdOrderByCreatedAtDescBudgetIdDesc(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No budget estimate exists yet for complaint " + complaintId));

        budget.setApprovedBy(null);
        budget.setApprovedAt(null);
        budget.setApprovalStatus(BudgetApprovalStatus.REJECTED);
        budgetRepository.save(budget);

        String safeNote = note == null ? "" : note.replace("\\", "\\\\").replace("\"", "\\\"");
        auditService.record(staff, "BUDGET_REJECTED", "COMPLAINT", complaintId,
                "{\"note\":\"" + safeNote + "\"}");

        return toResponse(complaint, true);
    }

    // ---- Manual reassignment (SRS 15.7 Features: "manual reassignment by Admin or Department Head") ----

    /**
     * PATCH .../complaints/{id}/assign - Phase 11's dedicated manual
     * reassignment action. See {@link com.jannetai.backend.dto.complaint.AssignmentRequest}'s
     * Javadoc for why this is a separate endpoint from
     * {@link #updateStatus}: it changes {@code department}/
     * {@code assignedOfficer} directly and is not itself a status
     * transition. If the complaint is still at {@code VERIFIED} (the
     * automatic {@link DepartmentAssignmentService} step somehow never
     * ran, or failed and was caught defensively - see that class's
     * Javadoc), this also performs the one-time VERIFIED -&gt; ASSIGNED
     * transition; otherwise the status is left untouched and only the
     * department/officer fields change.
     */
    @Transactional
    public ComplaintResponse reassign(User staff, Long complaintId, AssignmentRequest request) {
        Complaint complaint = requireComplaint(complaintId);
        // Phase 13 (Department Head Module) fix: this call was missing a
        // scope check entirely since Phase 11 - a DEPARTMENT_HEAD could
        // reassign a complaint in *any* department (not just their own)
        // purely by guessing/incrementing a complaint_id, and could name
        // an arbitrary target department to move it into. Closing this is
        // Phase 13's explicit "IMPORTANT SECURITY" requirement. A no-op
        // for ADMIN/SUPER_ADMIN (requireCanView imposes no restriction on
        // those roles - the SRS gives Admin cross-department authority).
        //
        // Note: for a DEPARTMENT_HEAD this also means a complaint that
        // somehow reached VERIFIED with no department at all yet (the
        // DepartmentAssignmentService-failed edge case this method's own
        // class Javadoc describes) can only be claimed by ADMIN/
        // SUPER_ADMIN, not by any Department Head - requireCanView treats
        // a null complaint.department as out of scope for that role,
        // which is correct: an unrouted complaint isn't yet "their"
        // department's queue to reach into.
        requireCanView(staff, complaint);
        if (staff.getRole() == Role.DEPARTMENT_HEAD
                && (staff.getDepartment() == null
                        || !staff.getDepartment().getDepartmentId().equals(request.departmentId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A Department Head may only reassign within their own department");
        }

        ComplaintStatus previous = complaint.getStatus();

        if (previous != ComplaintStatus.VERIFIED && previous != ComplaintStatus.ASSIGNED
                && previous != ComplaintStatus.IN_PROGRESS) {
            throw new InvalidStateTransitionException(
                    "Cannot reassign a complaint in status " + previous
                            + " - reassignment only applies once a complaint has reached VERIFIED or later "
                            + "and before it is RESOLVED/CLOSED/REJECTED/DUPLICATE");
        }

        Department department = departmentRepository.findById(request.departmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + request.departmentId()));

        User officer = null;
        if (request.officerId() != null) {
            officer = userRepository.findById(request.officerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Officer not found: " + request.officerId()));
            if (officer.getRole() != Role.GOVERNMENT_OFFICER) {
                throw new IllegalArgumentException("officerId must reference a GOVERNMENT_OFFICER user");
            }
            if (officer.getDepartment() == null
                    || !officer.getDepartment().getDepartmentId().equals(department.getDepartmentId())) {
                throw new IllegalArgumentException("The named officer does not belong to the target department");
            }
        }

        complaint.setDepartment(department);
        complaint.setAssignedOfficer(officer);

        ComplaintStatus next = previous == ComplaintStatus.VERIFIED ? ComplaintStatus.ASSIGNED : previous;
        if (next != previous) {
            ComplaintStateMachine.assertTransitionAllowed(previous, next, staff.getRole());
            complaint.setStatus(next);
        }
        complaint = complaintRepository.save(complaint);

        String reason = requireNonBlankOr(request.note(),
                "Manually reassigned to " + department.getName()
                        + (officer != null ? ", officer " + officer.getFullName() : " (department-level, no officer)"));
        recordHistory(complaint, previous, next, staff, ComplaintStateMachine.actorTypeFor(staff.getRole()), reason);

        auditService.record(staff, "COMPLAINT_MANUALLY_REASSIGNED", "COMPLAINT", complaint.getComplaintId(),
                "{\"department_id\":" + department.getDepartmentId()
                        + ",\"officer_id\":" + (officer != null ? officer.getUserId() : "null") + "}");

        // Phase 15: officer-specific "new complaint assigned" alert - distinct
        // from the citizen status alert recordHistory already sent above (this
        // fires even when next == previous, i.e. an officer swap within the
        // same status, which recordHistory's own dedup would otherwise miss).
        notificationService.notifyOfficerAssigned(complaint, officer);

        return toResponse(complaint, true);
    }

    // ---- Internal helpers ----

    // Package-private (not private): reused as-is by AiClassificationService
    // (Phase 8, same `service.complaint` package) so the AI orchestration
    // flow shares this module's existing complaint-lookup, status-history,
    // and response-building logic rather than duplicating it. No behavior
    // change - visibility only.
    Complaint requireComplaint(Long complaintId) {
        return complaintRepository.findById(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found: " + complaintId));
    }

    private void requireCanView(User requester, Complaint complaint) {
        if (requester.getRole() == Role.CITIZEN
                && !complaint.getCitizen().getUserId().equals(requester.getUserId())) {
            // SRS Table 23: "403 Forbidden (out of scope)".
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This complaint is not in your scope");
        }
        // Phase 12 (SRS 16.2 Officer Queue Permissions): the single-record
        // GET must enforce the same scope as the list endpoint, or a
        // GOVERNMENT_OFFICER/DEPARTMENT_HEAD could bypass their queue
        // restriction simply by guessing/incrementing a complaint_id.
        if (requester.getRole() == Role.GOVERNMENT_OFFICER
                && (complaint.getAssignedOfficer() == null
                        || !complaint.getAssignedOfficer().getUserId().equals(requester.getUserId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This complaint is not assigned to you");
        }
        if (requester.getRole() == Role.DEPARTMENT_HEAD
                && (complaint.getDepartment() == null
                        || requester.getDepartment() == null
                        || !complaint.getDepartment().getDepartmentId().equals(requester.getDepartment().getDepartmentId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This complaint is outside your department");
        }
        // VERIFICATION_TEAM/MAINTENANCE_TEAM/ADMIN/SUPER_ADMIN: allowed to
        // view any complaint, unchanged from Phase 6 - none of those roles
        // has an SRS-documented "own queue" restriction.
    }

    private void requireVerifiedIdentifier(User citizen) {
        if (citizen.getMobileVerifiedAt() == null && citizen.getEmailVerifiedAt() == null) {
            throw new UnverifiedIdentifierException(
                    "You must verify your mobile number or email before submitting your first complaint");
        }
    }

    private void requireWithinSubmissionLimit(User citizen) {
        long count = complaintRepository.countByCitizen_UserIdAndCreatedAtAfter(
                citizen.getUserId(), LocalDateTime.now().minusHours(24));
        if (count >= maxSubmissionsPer24h) {
            throw new ComplaintLimitExceededException(
                    "You have reached the maximum of " + maxSubmissionsPer24h
                            + " complaint submissions per 24 hours. Please try again later.");
        }
    }

    private void validatePhoto(MultipartFile photo) {
        // Phase 12 reuses this for the RESOLVED-mandatory after_photo (SRS
        // Table 8) as well as complaint creation's before-photo - the
        // "required" message is intentionally generic rather than
        // hardcoded to "submit a complaint" for that reason.
        if (photo == null || photo.isEmpty()) {
            throw new IllegalArgumentException("A photo is required");
        }
        if (photo.getSize() > MAX_PHOTO_BYTES) {
            throw new IllegalArgumentException("Photo must be under 10 MB");
        }
        String contentType = photo.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Photo must be JPEG, PNG, or WEBP");
        }
    }

    private void validateDescription(String description) {
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("Description must be " + MAX_DESCRIPTION_LENGTH + " characters or fewer");
        }
    }

    private void requireInRange(BigDecimal value, BigDecimal min, BigDecimal max, String fieldName) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max);
        }
    }

    private String generateReferenceNumber(Long complaintId) {
        // SRS Table 12's own example format: "JN-2026-000123".
        return "JN-%d-%06d".formatted(Year.now().getValue(), complaintId);
    }

    void recordHistory(Complaint complaint, ComplaintStatus previous, ComplaintStatus next,
                                User actor, com.jannetai.backend.entity.enums.ActorType actorType, String reason) {
        StatusHistory history = StatusHistory.builder()
                .complaint(complaint)
                .previousStatus(previous)
                .newStatus(next)
                .actor(actor)
                .actorType(actorType)
                .reason(reason)
                .build();
        statusHistoryRepository.save(history);
        // Phase 15 (Notification Module, SRS 15.13): every real status
        // transition this class produces (verify/updateStatus/reopen/
        // reassign) passes through here, so this single hook covers all
        // of them - notifyComplaintStatusChanged itself is a no-op for
        // any status not on the SRS's "major status changes" list (e.g.
        // SUBMITTED -> AI_PROCESSING) and for a no-op previous==next call.
        // NOTE: DepartmentAssignmentService's auto-assign path has its
        // OWN private recordHistory (not this one) and calls
        // notificationService directly - see that class.
        notificationService.notifyComplaintStatusChanged(complaint, previous, next);
    }

    ComplaintResponse toResponse(Complaint complaint) {
        return toResponse(complaint, false);
    }

    /**
     * Phase 12: {@code includeInternalNotes} lets citizen-facing call
     * sites (create/reopen/AiClassificationService's auto-verification
     * flow) keep using the safe no-notes default above, while staff-
     * facing call sites (getDetail for staff, updateStatus,
     * overrideClassification, escalate, addInternalNote, verify, assign,
     * approveBudget) pass {@code true} - see each call site.
     */
    ComplaintResponse toResponse(Complaint complaint, boolean includeInternalNotes) {
        List<ImageResponse> images = imageRepository
                .findByComplaint_ComplaintIdOrderByUploadedAtAsc(complaint.getComplaintId())
                .stream().map(image -> ImageResponse.from(image, storageService)).toList();
        List<StatusHistoryResponse> history = statusHistoryRepository
                .findByComplaint_ComplaintIdOrderByChangedAtAsc(complaint.getComplaintId())
                .stream().map(StatusHistoryResponse::from).toList();
        List<InternalNoteResponse> notes = includeInternalNotes
                ? auditLogRepository.findByEntityTypeAndEntityIdAndActionTypeOrderByCreatedAtAsc(
                        "COMPLAINT", complaint.getComplaintId(), "COMPLAINT_INTERNAL_NOTE_ADDED")
                        .stream().map(log -> InternalNoteResponse.from(log, extractNoteField(log.getDetails())))
                        .toList()
                : List.of();
        // Gap-backlog Patch 15/30/43 (Sep 2026 audit): both were already
        // computed/stored (Budget by PriorityBudgetPredictionService,
        // Prediction by AiClassificationService) but never reached this
        // response before this audit.
        BudgetResponse budgetResponse = budgetRepository
                .findFirstByComplaint_ComplaintIdOrderByCreatedAtDescBudgetIdDesc(complaint.getComplaintId())
                .map(b -> BudgetResponse.from(b, effectiveBudgetApprovalThresholdInr()))
                .orElse(null);
        AiClassificationResponse aiClassificationResponse = predictionRepository
                .findFirstByComplaint_ComplaintIdOrderByCreatedAtDescPredictionIdDesc(complaint.getComplaintId())
                .map(AiClassificationResponse::from)
                .orElse(null);
        return ComplaintResponse.from(complaint, images, history, notes, budgetResponse, aiClassificationResponse);
    }

    /**
     * Pulls {@code note} back out of the {@code {"note":"..."}} JSON this
     * class writes in {@link #addInternalNote} - a tiny hand-rolled
     * extraction rather than pulling in a JSON library dependency just
     * for this one field; consistent with {@link #escapeJson} already
     * being a hand-rolled escape rather than a library call.
     */
    private static String extractNoteField(String detailsJson) {
        if (detailsJson == null) {
            return "";
        }
        int start = detailsJson.indexOf("\"note\":\"");
        if (start < 0) {
            return "";
        }
        start += "\"note\":\"".length();
        int end = detailsJson.lastIndexOf("\"}");
        if (end < 0 || end < start) {
            return "";
        }
        return detailsJson.substring(start, end);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String requireNonBlankOr(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }
}
