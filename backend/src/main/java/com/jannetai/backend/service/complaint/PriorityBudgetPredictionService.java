package com.jannetai.backend.service.complaint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jannetai.backend.client.ai.AiBudgetPredictRequest;
import com.jannetai.backend.client.ai.AiBudgetPredictResult;
import com.jannetai.backend.client.ai.AiLocationSensitivityFlags;
import com.jannetai.backend.client.ai.AiPriorityPredictRequest;
import com.jannetai.backend.client.ai.AiPriorityPredictResult;
import com.jannetai.backend.client.ai.AiServiceCallException;
import com.jannetai.backend.client.ai.AiServiceClient;
import com.jannetai.backend.config.AiServiceProperties;
import com.jannetai.backend.entity.Budget;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Prediction;
import com.jannetai.backend.entity.enums.ConfidenceLevel;
import com.jannetai.backend.entity.enums.Severity;
import com.jannetai.backend.repository.BudgetRepository;
import com.jannetai.backend.repository.PredictionRepository;
import com.jannetai.backend.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 10 (Priority Prediction Module, SRS 15.8/21.6; Budget Prediction
 * Module, SRS 15.9/21.8).
 *
 * WIRING POINT - called whenever a complaint transitions to {@code
 * VERIFIED}, from both places that transition can happen:
 * <ul>
 *   <li>{@link AiClassificationService#applyAutoVerification} - the AI
 *       auto-verify path (Phase 8);</li>
 *   <li>{@link ComplaintService#verify} - the manual Verification Team
 *       override's {@code VERIFIED} decision (Phase 6).</li>
 * </ul>
 * This is a deliberate reading of SRS 14.1's own workflow narrative/
 * diagram (14.2, item 149): {@code "... Verified or Manual Verification
 * Queue -> [Duplicate Check] -> Duplicate Found (merged, end) or
 * (Severity/Priority/Budget Prediction) -> Assigned ..."} - the prediction
 * step runs once a complaint has a real, settled category and is known
 * not to be a duplicate, which is exactly what reaching {@code VERIFIED}
 * means under both paths (a confirmed AUTO_MERGE duplicate never reaches
 * {@code VERIFIED} at all - see {@link AiClassificationService#applyDuplicateMerge}
 * - so this class is never called for one). It deliberately does NOT run
 * inside {@link AiClassificationService#routeAfterChecks}'s
 * {@code MANUAL_REVIEW}/manual-review-park branches - a complaint sitting
 * at {@code AI_PROCESSING} with a placeholder {@code GENERAL} category
 * has nothing meaningful to score yet; prediction runs once, at the
 * moment a real category is locked in.
 *
 * SEVERITY OVERRIDE PRECEDENCE: SRS 15.8 Features explicitly lists
 * "manual override by Officer/Department Head with justification" as part
 * of this module - {@code VerificationDecisionRequest.severity} (Phase 6)
 * is exactly that override, arriving BEFORE this module ever runs (the
 * manual-verify path lets a human supply severity directly). When
 * {@code staffOverrideSeverity} is non-null, it always wins - this
 * module's own priority-predict call still happens (for the numeric
 * {@code priorityScore} and audit trail) but its severity output is
 * recorded only in {@code raw_model_output}, never applied to
 * {@code complaints.severity}.
 *
 * NEVER blocks the VERIFIED transition that already happened by the time
 * this is called - same never-let-an-AI-module-hiccup-block-a-citizen-or-
 * staff-workflow principle Phase 8/9 established for classify/duplicate-
 * check. A priority-predict or budget-predict failure is caught, logged,
 * audited, and simply leaves {@code complaints.severity} at whatever it
 * already was (staff-supplied or null) and/or skips writing a
 * {@code Budget} row - never rolls back or re-throws into the caller's
 * transaction.
 */
@Service
@RequiredArgsConstructor
public class PriorityBudgetPredictionService {

    private static final Logger log = LoggerFactory.getLogger(PriorityBudgetPredictionService.class);

    private final AiServiceClient aiServiceClient;
    private final AiServiceProperties aiServiceProperties;
    private final PredictionRepository predictionRepository;
    private final BudgetRepository budgetRepository;
    private final AuditService auditService;
    private final com.jannetai.backend.service.admin.SensitiveZoneService sensitiveZoneService; // audit GAP-033
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param complaint             must already be at {@code VERIFIED}
     *                              with a real (non-placeholder) category
     *                              set by the caller before this is
     *                              invoked.
     * @param staffOverrideSeverity non-null only on the manual-verify path
     *                              when the Verification Team supplied a
     *                              severity directly - see class Javadoc
     *                              "SEVERITY OVERRIDE PRECEDENCE".
     */
    @Transactional
    public void predictAndApply(Complaint complaint, Severity staffOverrideSeverity) {
        if (!aiServiceProperties.isEnabled()) {
            log.debug("ai-service integration disabled (app.ai-service.enabled=false); "
                    + "skipping priority/budget prediction for complaint {}", complaint.getComplaintId());
            return;
        }

        AiPriorityPredictResult priorityResult;
        try {
            priorityResult = callPriorityPredict(complaint);
        } catch (AiServiceCallException e) {
            log.warn("ai-service priority-predict failed for complaint {}: [{}] {}",
                    complaint.getComplaintId(), e.getErrorCode(), e.getMessage());
            auditService.record(null, "AI_PRIORITY_PREDICTION_FAILED", "COMPLAINT", complaint.getComplaintId(),
                    toJson(Map.of("error_code", e.getErrorCode(), "message", nullToEmpty(e.getMessage()))));
            return;
        }

        boolean severityOverridden = staffOverrideSeverity != null;
        Severity finalSeverity = severityOverridden ? staffOverrideSeverity : priorityResult.severity();
        complaint.setSeverity(finalSeverity);

        AiBudgetPredictResult budgetResult = null;
        try {
            budgetResult = callBudgetPredict(complaint, finalSeverity);
        } catch (AiServiceCallException e) {
            log.warn("ai-service budget-predict failed for complaint {}: [{}] {}",
                    complaint.getComplaintId(), e.getErrorCode(), e.getMessage());
            auditService.record(null, "AI_BUDGET_PREDICTION_FAILED", "COMPLAINT", complaint.getComplaintId(),
                    toJson(Map.of("error_code", e.getErrorCode(), "message", nullToEmpty(e.getMessage()))));
            // Severity was already successfully assigned above - a budget
            // failure doesn't undo that; only the Budget row is skipped.
        }

        persistPredictionUpdate(complaint, priorityResult, budgetResult, severityOverridden);
        if (budgetResult != null) {
            persistBudget(complaint, budgetResult);
        }

        log.info("Complaint {} severity={} (staff_override={}) priority_score={}{}",
                complaint.getComplaintId(), finalSeverity, severityOverridden, priorityResult.priorityScore(),
                budgetResult != null
                        ? String.format(", estimated_cost=%s-%s INR, resolution_days=%s",
                                budgetResult.estimatedCostMin(), budgetResult.estimatedCostMax(),
                                budgetResult.estimatedResolutionDays())
                        : ", budget estimate unavailable this attempt");

        auditService.record(null, "AI_PRIORITY_BUDGET_PREDICTED", "COMPLAINT", complaint.getComplaintId(),
                toJson(fullAuditPayload(finalSeverity, severityOverridden, priorityResult, budgetResult)));
    }

    private AiPriorityPredictResult callPriorityPredict(Complaint complaint) {
        AiPriorityPredictRequest request = new AiPriorityPredictRequest(
                complaint.getCategory(),
                complaint.getCorroborationCount() != null ? complaint.getCorroborationCount() : 1,
                // Audit GAP-033 (SRS 15.8): flags from the Admin-recorded sensitive
                // zones (V29); NONE when no zone matches or the location is approximate.
                sensitiveZoneService.flagsFor(complaint.getLocation()),
                complaint.getComplaintId());
        return aiServiceClient.predictPriority(request);
    }

    private AiBudgetPredictResult callBudgetPredict(Complaint complaint, Severity finalSeverity) {
        Long wardId = (complaint.getLocation() != null && complaint.getLocation().getWard() != null)
                ? complaint.getLocation().getWard().getWardId() : null;
        AiBudgetPredictRequest request = new AiBudgetPredictRequest(
                complaint.getCategory(), finalSeverity, wardId, complaint.getComplaintId());
        return aiServiceClient.predictBudget(request);
    }

    /**
     * Updates the most recent {@link Prediction} row for this complaint
     * (per {@link PredictionRepository}'s "latest row is authoritative"
     * convention, V8 migration) with {@code predictedSeverity}/
     * {@code priorityScore}, merging the priority/budget detail into that
     * row's existing {@code raw_model_output} JSON rather than discarding
     * whatever classify/duplicate-check already wrote there. If no
     * Prediction row exists yet for this complaint (the manual-verify path
     * when ai-service was entirely disabled/unreachable at submission
     * time, so classify never ran and never inserted one), a new row is
     * created - {@code aiConfidence} has no real classification-confidence
     * meaning in that case and is set to a documented {@code 0.00}
     * sentinel purely to satisfy the V8 migration's {@code NOT NULL}
     * constraint on a column that was designed around the classify
     * pipeline, not this one.
     */
    private void persistPredictionUpdate(Complaint complaint, AiPriorityPredictResult priorityResult,
                                          AiBudgetPredictResult budgetResult, boolean severityOverridden) {
        Prediction prediction = predictionRepository
                .findFirstByComplaint_ComplaintIdOrderByCreatedAtDescPredictionIdDesc(complaint.getComplaintId())
                .orElse(null);

        Map<String, Object> priorityBudgetPayload = priorityBudgetPayload(priorityResult, budgetResult, severityOverridden);

        if (prediction == null) {
            prediction = Prediction.builder()
                    .complaint(complaint)
                    .aiConfidence(BigDecimal.ZERO)
                    .modelVersion(priorityResult.modelVersion())
                    .duplicateFlag(false)
                    .predictedSeverity(priorityResult.severity())
                    .priorityScore(BigDecimal.valueOf(priorityResult.priorityScore()))
                    .rawModelOutput(toJson(Map.of("priority_predict_and_budget_predict", priorityBudgetPayload)))
                    .build();
            predictionRepository.save(prediction);
            return;
        }

        prediction.setPredictedSeverity(priorityResult.severity());
        prediction.setPriorityScore(BigDecimal.valueOf(priorityResult.priorityScore()));
        prediction.setRawModelOutput(mergeRawModelOutput(prediction.getRawModelOutput(), priorityBudgetPayload));
        predictionRepository.save(prediction);
    }

    private void persistBudget(Complaint complaint, AiBudgetPredictResult budgetResult) {
        Budget budget = Budget.builder()
                .complaint(complaint)
                .estimatedCostMin(budgetResult.estimatedCostMin())
                .estimatedCostMax(budgetResult.estimatedCostMax())
                .estimatedResolutionDays(budgetResult.estimatedResolutionDays())
                .confidenceLevel(ConfidenceLevel.valueOf(budgetResult.confidence()))
                // SRS 15.9 Business Rules: "estimates above a configurable
                // threshold require Department Head approval before the
                // complaint can move to In Progress." NOT enforced this
                // phase - Department Assignment (Phase 11) doesn't exist
                // yet, so a complaint structurally cannot reach In Progress
                // through this backend regardless (see ARCHITECTURE.md
                // Section 4). approvedBy is left null; a future phase that
                // builds the approval gate can populate it.
                .approvedBy(null)
                .build();
        budgetRepository.save(budget);
    }

    /**
     * Merges the new priority/budget detail into an existing classify-
     * produced {@code raw_model_output} JSON object (adds/replaces a
     * {@code priority_predict_and_budget_predict} key, leaving
     * {@code classify}/{@code duplicate_check} - Phase 8/9's own keys -
     * untouched). Falls back to a fresh object containing only this
     * phase's payload if the existing value is null/unparseable (should
     * only happen for a hand-edited or pre-Phase-8 row).
     */
    private String mergeRawModelOutput(String existingJson, Map<String, Object> priorityBudgetPayload) {
        Map<String, Object> merged;
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(existingJson, Map.class);
                merged = new LinkedHashMap<>(parsed);
            } catch (Exception e) {
                log.warn("Could not parse existing raw_model_output for complaint {} as JSON; "
                                + "overwriting with priority/budget payload only: {}",
                        existingJson, e.getMessage());
                merged = new LinkedHashMap<>();
            }
        } else {
            merged = new LinkedHashMap<>();
        }
        merged.put("priority_predict_and_budget_predict", priorityBudgetPayload);
        return toJson(merged);
    }

    private Map<String, Object> priorityBudgetPayload(AiPriorityPredictResult priorityResult,
                                                        AiBudgetPredictResult budgetResult, boolean severityOverridden) {
        Map<String, Object> priorityPayload = new LinkedHashMap<>();
        priorityPayload.put("severity", priorityResult.severity().name());
        priorityPayload.put("priority_score", priorityResult.priorityScore());
        priorityPayload.put("safety_hazard_override_applied", priorityResult.safetyHazardOverrideApplied());
        priorityPayload.put("corroboration_bump_applied", priorityResult.corroborationBumpApplied());
        priorityPayload.put("base_severity", priorityResult.baseSeverity().name());
        priorityPayload.put("model_version", priorityResult.modelVersion());
        priorityPayload.put("staff_severity_override_applied", severityOverridden);
        priorityPayload.put("raw_output", priorityResult.rawOutput());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("priority_predict", priorityPayload);

        if (budgetResult != null) {
            Map<String, Object> budgetPayload = new LinkedHashMap<>();
            budgetPayload.put("estimated_cost_min", budgetResult.estimatedCostMin());
            budgetPayload.put("estimated_cost_max", budgetResult.estimatedCostMax());
            budgetPayload.put("estimated_resolution_days", budgetResult.estimatedResolutionDays());
            budgetPayload.put("confidence", budgetResult.confidence());
            budgetPayload.put("model_version", budgetResult.modelVersion());
            budgetPayload.put("guardrail_applied", budgetResult.guardrailApplied());
            budgetPayload.put("raw_output", budgetResult.rawOutput());
            payload.put("budget_predict", budgetPayload);
        } else {
            payload.put("budget_predict", Map.of("attempted", true, "succeeded", false,
                    "reason", "failed - see AI_BUDGET_PREDICTION_FAILED audit entry"));
        }

        return payload;
    }

    private Map<String, Object> fullAuditPayload(Severity finalSeverity, boolean severityOverridden,
                                                   AiPriorityPredictResult priorityResult, AiBudgetPredictResult budgetResult) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("final_severity", finalSeverity.name());
        payload.put("staff_severity_override_applied", severityOverridden);
        payload.put("ai_predicted_severity", priorityResult.severity().name());
        payload.put("priority_score", priorityResult.priorityScore());
        payload.put("budget_predicted", budgetResult != null);
        if (budgetResult != null) {
            payload.put("estimated_cost_min", budgetResult.estimatedCostMin());
            payload.put("estimated_cost_max", budgetResult.estimatedCostMax());
            payload.put("estimated_resolution_days", budgetResult.estimatedResolutionDays());
            payload.put("confidence", budgetResult.confidence());
        }
        return payload;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize priority/budget prediction audit payload: {}", e.getMessage());
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
