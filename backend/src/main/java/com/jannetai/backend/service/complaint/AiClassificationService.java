package com.jannetai.backend.service.complaint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jannetai.backend.client.ai.AiClassifyRequest;
import com.jannetai.backend.client.ai.AiClassifyResult;
import com.jannetai.backend.client.ai.AiDuplicateCheckResult;
import com.jannetai.backend.client.ai.AiDuplicateMatchTier;
import com.jannetai.backend.client.ai.AiServiceCallException;
import com.jannetai.backend.client.ai.AiServiceClient;
import com.jannetai.backend.config.AiServiceProperties;
import com.jannetai.backend.dto.complaint.ComplaintResponse;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Image;
import com.jannetai.backend.entity.Prediction;
import com.jannetai.backend.entity.enums.ActorType;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.ImageType;
import com.jannetai.backend.repository.ImageRepository;
import com.jannetai.backend.repository.PredictionRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 8 (AI Analysis Module &lt;-&gt; Complaint Module integration; SRS
 * 15.4) plus Phase 9 (Duplicate Detection Module; SRS 15.6/21.5) plus
 * Phase 10 (Priority/Budget Prediction Modules; SRS 15.8/15.9/21.6/21.8 -
 * see {@link #applyAutoVerification}'s final step, delegated to
 * {@link PriorityBudgetPredictionService}).
 *
 * Called once, right after {@link ComplaintService#create} has committed a
 * new complaint at {@code AI_PROCESSING} (Phase 6's synchronous
 * SUBMITTED -&gt; AI_PROCESSING transition, unchanged). Per-call sequence:
 *
 * <ol>
 *   <li>calls ai-service's {@code POST /api/v1/ai/classify} (Phase 8,
 *       unchanged this phase) - a failure here (unreachable, timeout,
 *       error, or {@code app.ai-service.enabled=false}) is caught, logged,
 *       audited ({@code AI_CLASSIFICATION_FAILED}), and the complaint is
 *       left parked at {@code AI_PROCESSING}; duplicate-checking is
 *       skipped entirely in that case (an unreachable ai-service means the
 *       whole AI module is down, not just classification);</li>
 *   <li>NEW Phase 9: on a successful classify, calls
 *       {@link DuplicateDetectionService#check} - an
 *       {@link AiServiceCallException} here (ai-service reachable for
 *       classify but the duplicate-check call itself failed) is caught,
 *       logged, audited ({@code AI_DUPLICATE_CHECK_FAILED}) and treated as
 *       "not a duplicate", not a fatal error - classify-only routing still
 *       proceeds using whatever the classify call already returned;</li>
 *   <li>always persists a single {@link Prediction} row combining both
 *       calls' output, for audit/retraining (SRS 19.6), regardless of the
 *       routing outcome - this is the first phase to write a real (not
 *       hardcoded-false) {@code duplicate_flag};</li>
 *   <li>applies the combined routing rule (SRS 15.4 Business Rules + 15.6
 *       "Decision Point — Duplicate Outcome", see
 *       {@link #routeAfterChecks} for the exact priority order): a
 *       confirmed duplicate (AUTO_MERGE tier) always wins over classify's
 *       own verdict and moves the complaint straight to
 *       {@code DUPLICATE}, linked to its parent; a borderline duplicate
 *       (MANUAL_REVIEW tier) parks the complaint for a human regardless of
 *       how confident classify was; only when duplicate-checking found
 *       nothing (NOT_DUPLICATE/NO_CANDIDATES/skipped/failed) does
 *       classify's own {@code requires_manual_review} verdict decide
 *       auto-verify vs. manual review, exactly as Phase 8 already did;</li>
 *   <li>NEW Phase 10: when routing lands on auto-verify
 *       ({@link #applyAutoVerification}), the final step there delegates to
 *       {@link PriorityBudgetPredictionService#predictAndApply} - assigns
 *       severity (SRS 15.8), then a cost/resolution-time estimate (SRS
 *       15.9), and updates this same {@link Prediction} row plus a new
 *       {@link com.jannetai.backend.entity.Budget} row. Not reached for a
 *       {@code DUPLICATE} or manual-review outcome - see that service's
 *       class Javadoc "WIRING POINT" for the full reasoning;</li>
 *   <li>NEVER lets any ai-service call's failure propagate out of this
 *       class - a citizen's complaint submission must keep succeeding
 *       whether or not ai-service is up, identical to Phase 6's
 *       pre-Phase-8 behavior for classify, now extended to duplicate-check
 *       and priority/budget prediction too.</li>
 * </ol>
 *
 * KNOWN LIMITATION (documented, not fixed this phase): the outbound HTTP
 * calls happen inside a {@code @Transactional} method, holding a DB
 * connection open for their combined duration (now up to four blocking
 * calls on the auto-verify path: classify, duplicate-check, priority-
 * predict, budget-predict). Same acceptable-at-this-project's-scale
 * tradeoff Phase 8 already logged (PROJECT_INTEGRATION.md Section 6) - not
 * revisited this phase, no demonstrated load problem exists yet.
 */
@Service
@RequiredArgsConstructor
public class AiClassificationService {

    private static final Logger log = LoggerFactory.getLogger(AiClassificationService.class);

    private final ComplaintService complaintService;
    private final ImageRepository imageRepository;
    private final PredictionRepository predictionRepository;
    private final StorageService storageService;
    private final AuditService auditService;
    private final AiServiceClient aiServiceClient;
    private final AiServiceProperties aiServiceProperties;
    private final DuplicateDetectionService duplicateDetectionService;
    private final PriorityBudgetPredictionService priorityBudgetPredictionService;
    private final DepartmentAssignmentService departmentAssignmentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public ComplaintResponse classifyAndRoute(Long complaintId) {
        Complaint complaint = complaintService.requireComplaint(complaintId);

        // Idempotency guard: only meaningful to call from AI_PROCESSING.
        // Defensive only - ComplaintController calls this exactly once,
        // immediately after ComplaintService.create() returns.
        if (complaint.getStatus() != ComplaintStatus.AI_PROCESSING) {
            return complaintService.toResponse(complaint);
        }

        if (!aiServiceProperties.isEnabled()) {
            log.debug("ai-service integration disabled (app.ai-service.enabled=false); "
                    + "leaving complaint {} parked at AI_PROCESSING for manual review", complaintId);
            return complaintService.toResponse(complaint);
        }

        AiClassifyResult classifyResult;
        try {
            classifyResult = callClassify(complaint);
        } catch (AiServiceCallException e) {
            log.warn("ai-service classification failed for complaint {}: [{}] {}",
                    complaintId, e.getErrorCode(), e.getMessage());
            auditService.record(null, "AI_CLASSIFICATION_FAILED", "COMPLAINT", complaintId,
                    toJson(Map.of("error_code", e.getErrorCode(), "message", nullToEmpty(e.getMessage()))));
            return complaintService.toResponse(complaint);
        }

        AiDuplicateCheckResult duplicateResult = attemptDuplicateCheck(complaint);

        persistPrediction(complaint, classifyResult, duplicateResult);
        routeAfterChecks(complaint, classifyResult, duplicateResult);

        return complaintService.toResponse(complaint);
    }

    /**
     * Never throws - a duplicate-check failure must not block classify-only
     * routing (see class Javadoc, point 2). Returns {@code null} both when
     * {@link DuplicateDetectionService#check} legitimately found nothing to
     * compare against (ward unresolved) and when the call itself failed;
     * {@link #routeAfterChecks} treats both identically as "not a
     * duplicate" - callers here don't need to distinguish the two.
     */
    private AiDuplicateCheckResult attemptDuplicateCheck(Complaint complaint) {
        try {
            return duplicateDetectionService.check(complaint);
        } catch (AiServiceCallException e) {
            log.warn("ai-service duplicate-check failed for complaint {}: [{}] {}",
                    complaint.getComplaintId(), e.getErrorCode(), e.getMessage());
            auditService.record(null, "AI_DUPLICATE_CHECK_FAILED", "COMPLAINT", complaint.getComplaintId(),
                    toJson(Map.of("error_code", e.getErrorCode(), "message", nullToEmpty(e.getMessage()))));
            return null;
        }
    }

    /**
     * The combined routing decision (SRS 15.4 + 15.6's "Decision Point —
     * Duplicate Outcome"). Priority order, highest first:
     * <ol>
     *   <li>AUTO_MERGE duplicate match -&gt; {@link #applyDuplicateMerge}
     *       (wins over classify's own verdict entirely - a confirmed
     *       duplicate is never independently auto-verified);</li>
     *   <li>MANUAL_REVIEW (borderline) duplicate match -&gt; parked at
     *       {@code AI_PROCESSING} for a human either way, regardless of how
     *       confident classify was about the category;</li>
     *   <li>otherwise (no duplicate found, duplicate-check skipped, or
     *       duplicate-check failed) -&gt; classify's own
     *       {@code requires_manual_review} decides, exactly as Phase 8.</li>
     * </ol>
     */
    private void routeAfterChecks(Complaint complaint, AiClassifyResult classifyResult,
                                   AiDuplicateCheckResult duplicateResult) {
        AiDuplicateMatchTier tier = duplicateResult != null ? duplicateResult.matchTier() : null;

        if (tier == AiDuplicateMatchTier.AUTO_MERGE) {
            applyDuplicateMerge(complaint, duplicateResult);
            return;
        }

        if (tier == AiDuplicateMatchTier.MANUAL_REVIEW) {
            log.info("Complaint {} is a possible duplicate of complaint {} (similarity={}); "
                            + "parked at AI_PROCESSING for Verification Team confirmation",
                    complaint.getComplaintId(), duplicateResult.parentComplaintId(), duplicateResult.similarityScore());
            auditService.record(null, "AI_DUPLICATE_REQUIRES_MANUAL_REVIEW", "COMPLAINT", complaint.getComplaintId(),
                    toJson(Map.of(
                            "candidate_parent_complaint_id", nullToZero(duplicateResult.parentComplaintId()),
                            "similarity_score", duplicateResult.similarityScore())));
            return;
        }

        if (!classifyResult.requiresManualReview()) {
            applyAutoVerification(complaint, classifyResult);
        } else {
            log.info("Complaint {} requires manual review (routing_reason={})",
                    complaint.getComplaintId(), classifyResult.routingReason());
            auditService.record(null, "AI_CLASSIFICATION_REQUIRES_MANUAL_REVIEW", "COMPLAINT", complaint.getComplaintId(),
                    toJson(Map.of(
                            "category", classifyResult.category().name(),
                            "confidence", classifyResult.confidence(),
                            "routing_reason", classifyResult.routingReason())));
        }
    }

    private AiClassifyResult callClassify(Complaint complaint) {
        Image beforePhoto = imageRepository
                .findFirstByComplaint_ComplaintIdAndImageTypeOrderByUploadedAtAsc(
                        complaint.getComplaintId(), ImageType.BEFORE)
                .orElseThrow(() -> new AiServiceCallException("NO_IMAGE_ON_FILE",
                        "Complaint " + complaint.getComplaintId() + " has no BEFORE photo to classify"));

        byte[] imageBytes = storageService.load(beforePhoto.getStorageKey());
        String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);

        AiClassifyRequest request = new AiClassifyRequest(
                imageBase64, complaint.getDescription(), null, complaint.getComplaintId());
        return aiServiceClient.classify(request);
    }

    private void persistPrediction(Complaint complaint, AiClassifyResult classifyResult,
                                    AiDuplicateCheckResult duplicateResult) {
        Prediction prediction = Prediction.builder()
                .complaint(complaint)
                .aiConfidence(BigDecimal.valueOf(classifyResult.confidence()))
                .modelVersion(classifyResult.modelVersion())
                // Severity/priority scoring is the Priority Prediction
                // Module's job (Phase 10) - deliberately still left null at
                // THIS point in the flow: this row is written right after
                // classify/duplicate-check, before routeAfterChecks has
                // decided whether the complaint even reaches VERIFIED.
                // PriorityBudgetPredictionService (called from
                // applyAutoVerification, below) updates this SAME row's
                // predictedSeverity/priorityScore once that's known - see
                // PredictionRepository's "latest row is authoritative"
                // convention. A complaint that ends up AUTO_MERGE'd or
                // parked for manual review never gets this row updated,
                // and these two columns correctly stay null for it.
                .predictedSeverity(null)
                .priorityScore(null)
                // Phase 9: the first phase to write a real value here -
                // true only for a confirmed (AUTO_MERGE) match. A
                // MANUAL_REVIEW borderline match is NOT flagged true here -
                // it isn't a confirmed duplicate yet, only a candidate one;
                // see routeAfterChecks/AI_DUPLICATE_REQUIRES_MANUAL_REVIEW
                // for where that candidate info actually lives (audit log +
                // this same row's raw_model_output.duplicate_check).
                .duplicateFlag(duplicateResult != null && duplicateResult.isDuplicate())
                .rawModelOutput(toJson(fullAuditPayload(classifyResult, duplicateResult)))
                .build();
        predictionRepository.save(prediction);
    }

    private void applyAutoVerification(Complaint complaint, AiClassifyResult result) {
        ComplaintStateMachine.assertSystemTransitionAllowed(ComplaintStatus.AI_PROCESSING, ComplaintStatus.VERIFIED);
        ComplaintStatus previous = complaint.getStatus();
        complaint.setCategory(result.category());
        complaint.setStatus(ComplaintStatus.VERIFIED);

        complaintService.recordHistory(complaint, previous, ComplaintStatus.VERIFIED, null, ActorType.SYSTEM,
                "Auto-verified by AI Analysis Module (category=" + result.category()
                        + ", confidence=" + result.confidence() + "%)");

        auditService.record(null, "AI_CLASSIFICATION_AUTO_VERIFIED", "COMPLAINT", complaint.getComplaintId(),
                toJson(Map.of(
                        "category", result.category().name(),
                        "confidence", result.confidence(),
                        "model_version", result.modelVersion())));

        log.info("Complaint {} auto-verified by AI Analysis Module (category={}, confidence={})",
                complaint.getComplaintId(), result.category(), result.confidence());

        // Phase 10 (SRS 15.8/15.9): the complaint has a real category and
        // is confirmed not a duplicate the moment it reaches VERIFIED -
        // see PriorityBudgetPredictionService's class Javadoc "WIRING
        // POINT" for the full SRS 14.1/14.2 workflow-ordering reasoning.
        // staffOverrideSeverity is always null on this fully-automated
        // path (no human involved yet).
        priorityBudgetPredictionService.predictAndApply(complaint, null);

        // Phase 11 (SRS 15.7): runs immediately after prediction, matching
        // SRS 14.2's own workflow diagram ordering - see
        // DepartmentAssignmentService's class Javadoc "WIRING POINT".
        // Transitions VERIFIED -> ASSIGNED; never blocks this method even
        // if it fails internally (see that class's own defensive posture).
        departmentAssignmentService.assignAndApply(complaint);
    }

    /**
     * Phase 9: a confirmed duplicate (AUTO_MERGE tier) is transitioned
     * straight from {@code AI_PROCESSING -> DUPLICATE} - never through
     * {@code VERIFIED} - and linked to its parent, mirroring exactly what
     * {@link ComplaintService#verify}'s manual DUPLICATE decision already
     * does for the human-reviewed path (same parent-link +
     * corroboration_count-increment behavior, just system-initiated
     * instead of staff-initiated). The complaint's own {@code category} is
     * deliberately left unset (still {@code GENERAL}, Phase 6's
     * placeholder) - a merged duplicate is never independently classified,
     * same as the manual DUPLICATE path.
     */
    private void applyDuplicateMerge(Complaint complaint, AiDuplicateCheckResult duplicateResult) {
        Complaint parent = complaintService.requireComplaint(duplicateResult.parentComplaintId());

        ComplaintStateMachine.assertSystemTransitionAllowed(ComplaintStatus.AI_PROCESSING, ComplaintStatus.DUPLICATE);
        ComplaintStatus previous = complaint.getStatus();
        complaint.setParentComplaint(parent);
        complaint.setStatus(ComplaintStatus.DUPLICATE);

        // Relies on @Transactional dirty-checking to persist both this
        // status change and the parent's corroboration_count bump - same
        // pattern applyAutoVerification already uses in this class (no
        // complaintRepository is injected here; ComplaintService owns
        // that). ComplaintService#verify's manual DUPLICATE decision
        // additionally calls complaintRepository.save(parent) explicitly,
        // but that's redundant under the same dirty-checking guarantee,
        // not a behavioral difference.
        parent.setCorroborationCount(parent.getCorroborationCount() + 1);

        String reason = "Auto-merged by Duplicate Detection Module as a duplicate of "
                + parent.getReferenceNumber() + " (similarity=" + duplicateResult.similarityScore() + "%)";
        complaintService.recordHistory(complaint, previous, ComplaintStatus.DUPLICATE, null, ActorType.SYSTEM, reason);

        auditService.record(null, "AI_DUPLICATE_AUTO_MERGED", "COMPLAINT", complaint.getComplaintId(),
                toJson(Map.of(
                        "parent_complaint_id", parent.getComplaintId(),
                        "parent_reference_number", parent.getReferenceNumber(),
                        "similarity_score", duplicateResult.similarityScore(),
                        "updated_corroboration_count", parent.getCorroborationCount())));

        log.info("Complaint {} auto-merged as a duplicate of complaint {} (similarity={})",
                complaint.getComplaintId(), parent.getComplaintId(), duplicateResult.similarityScore());
    }

    /** Full ai-service response(s), kept for audit/retraining per SRS 19.6 - nothing from either call is discarded. */
    private Map<String, Object> fullAuditPayload(AiClassifyResult classifyResult, AiDuplicateCheckResult duplicateResult) {
        Map<String, Object> classifyPayload = new LinkedHashMap<>();
        classifyPayload.put("category", classifyResult.category().name());
        classifyPayload.put("confidence", classifyResult.confidence());
        classifyPayload.put("gemini_description", classifyResult.geminiDescription());
        classifyPayload.put("ocr_text", classifyResult.ocrText());
        classifyPayload.put("requires_manual_review", classifyResult.requiresManualReview());
        classifyPayload.put("routing_reason", classifyResult.routingReason());
        classifyPayload.put("model_version", classifyResult.modelVersion());
        classifyPayload.put("model_available", classifyResult.modelAvailable());
        classifyPayload.put("image_quality_flag", classifyResult.imageQualityFlag());
        classifyPayload.put("gemini_used", classifyResult.geminiUsed());
        classifyPayload.put("top_candidates", classifyResult.topCandidates());
        classifyPayload.put("raw_model_output", classifyResult.rawModelOutput());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("classify", classifyPayload);
        payload.put("duplicate_check", duplicateResult != null ? duplicateCheckPayload(duplicateResult)
                : Map.of("attempted", false, "reason", "skipped or failed - see AI_DUPLICATE_CHECK_FAILED audit entry if present"));
        return payload;
    }

    private Map<String, Object> duplicateCheckPayload(AiDuplicateCheckResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attempted", true);
        payload.put("is_duplicate", result.isDuplicate());
        payload.put("parent_complaint_id", nullToZero(result.parentComplaintId()));
        payload.put("similarity_score", result.similarityScore());
        payload.put("requires_manual_review", result.requiresManualReview());
        payload.put("match_tier", result.matchTier().name());
        payload.put("threshold_used", result.thresholdUsed());
        payload.put("gps_available", result.gpsAvailable());
        payload.put("model_version", result.modelVersion());
        payload.put("top_matches", result.topMatches());
        payload.put("raw_output", result.rawOutput());
        return payload;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize AI classification audit payload: {}", e.getMessage());
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }
}
