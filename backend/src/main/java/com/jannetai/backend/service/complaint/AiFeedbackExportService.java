package com.jannetai.backend.service.complaint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Prediction;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.PredictionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;

/**
 * Gap-backlog Patch 26/34 (Sep 2026 audit): "AI Feedback Loop" -
 * exports, for every complaint that has been through human verification,
 * both the AI's original prediction and the human's final decision, so a
 * future retraining pass has a real dataset instead of nothing. This is
 * the export step only ("Verified Complaints -> Training Dataset" in the
 * patch's own diagram) - actually retraining a new model version from
 * this export, evaluating it, and deploying it (the rest of that
 * diagram) is Gap-backlog Patch 51's continuous-training-pipeline scope,
 * deliberately not built here (needs a real ML training environment this
 * sandbox doesn't have).
 *
 * <p>The AI's ORIGINAL predicted category is reconstructed from
 * {@code Prediction.rawModelOutput}'s {@code yolo.detections[0].class_name}
 * (the raw YOLO class, before {@code AiClassificationService}'s
 * {@code _map_category}-equivalent mapping) - {@code Complaint.category}
 * itself is NOT the AI's original prediction once a human has verified
 * the complaint: {@code ComplaintService.verify} overwrites it with
 * whatever the verifier chose, even when they simply accepted the AI's
 * own suggestion. Comparing the two is the entire point of this export.
 */
@Service
@RequiredArgsConstructor
public class AiFeedbackExportService {

    private static final Logger log = LoggerFactory.getLogger(AiFeedbackExportService.class);

    /** Every status a complaint only reaches after passing through {@code ComplaintService.verify} - excludes SUBMITTED/AI_PROCESSING, which have no human decision yet. */
    private static final EnumSet<ComplaintStatus> VERIFIED_STATUSES = EnumSet.of(
            ComplaintStatus.VERIFIED, ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS,
            ComplaintStatus.RESOLVED, ComplaintStatus.CLOSED, ComplaintStatus.REJECTED, ComplaintStatus.DUPLICATE);

    private final ComplaintRepository complaintRepository;
    private final PredictionRepository predictionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional(readOnly = true)
    public String exportCsv() {
        List<Complaint> complaints = complaintRepository.findByStatusIn(VERIFIED_STATUSES);

        StringBuilder csv = new StringBuilder();
        csv.append("complaint_id,ai_predicted_class,ai_confidence,model_version,human_final_category,decision_status,duplicate_flagged\n");

        for (Complaint complaint : complaints) {
            Prediction prediction = predictionRepository
                    .findFirstByComplaint_ComplaintIdOrderByCreatedAtDescPredictionIdDesc(complaint.getComplaintId())
                    .orElse(null);
            if (prediction == null) {
                // A complaint can reach a verified status with no
                // Prediction row at all if ai-service was unreachable at
                // submission time and a human verified it purely
                // manually (AiClassificationService's own documented
                // fallback) - correctly excluded from a feedback dataset
                // whose whole point is comparing an AI prediction against
                // a human decision; there is no AI prediction to compare.
                continue;
            }
            String aiPredictedClass = extractTopYoloClass(prediction.getRawModelOutput());
            csv.append(complaint.getComplaintId()).append(',')
                    .append(csvSafe(aiPredictedClass)).append(',')
                    .append(prediction.getAiConfidence() != null ? prediction.getAiConfidence().toString() : "").append(',')
                    .append(csvSafe(prediction.getModelVersion())).append(',')
                    .append(csvSafe(complaint.getCategory() != null ? complaint.getCategory().name() : "")).append(',')
                    .append(csvSafe(complaint.getStatus().name())).append(',')
                    .append(Boolean.TRUE.equals(prediction.getDuplicateFlag())).append('\n');
        }
        return csv.toString();
    }

    /**
     * Gap-backlog Patch 52 (Sep 2026 strict recheck): manual-override rate
     * overall and per AI-predicted class - a real drift signal derived from
     * actual human decisions. Only complaints a human VERIFIED (not
     * REJECTED/DUPLICATE, which decide something other than category) and
     * that have an AI class are counted. YOLO class names map onto
     * ComplaintCategory by upper-casing (pothole -> POTHOLE ...), the same
     * one-to-one mapping ai-service's _map_category applies.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> overrideSummary() {
        java.util.Map<String, int[]> perClass = new java.util.TreeMap<>(); // [total, overridden]
        int total = 0;
        int overridden = 0;
        for (Complaint complaint : complaintRepository.findByStatusIn(VERIFIED_STATUSES)) {
            if (complaint.getStatus() == ComplaintStatus.REJECTED || complaint.getStatus() == ComplaintStatus.DUPLICATE
                    || complaint.getCategory() == null) {
                continue;
            }
            Prediction prediction = predictionRepository
                    .findFirstByComplaint_ComplaintIdOrderByCreatedAtDescPredictionIdDesc(complaint.getComplaintId())
                    .orElse(null);
            if (prediction == null) {
                continue;
            }
            String aiClass = extractTopYoloClass(prediction.getRawModelOutput());
            if (aiClass.isEmpty()) {
                continue;
            }
            boolean changed = !aiClass.toUpperCase(java.util.Locale.ROOT).equals(complaint.getCategory().name());
            int[] c = perClass.computeIfAbsent(aiClass, k -> new int[2]);
            c[0]++;
            total++;
            if (changed) {
                c[1]++;
                overridden++;
            }
        }
        java.util.Map<String, Object> perClassOut = new java.util.LinkedHashMap<>();
        perClass.forEach((cls, c) -> perClassOut.put(cls, java.util.Map.of(
                "reviewed", c[0], "overridden", c[1],
                "overrideRatePercent", Math.round(1000.0 * c[1] / c[0]) / 10.0)));
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("reviewedWithAiPrediction", total);
        out.put("overridden", overridden);
        out.put("overrideRatePercent", total == 0 ? null : Math.round(1000.0 * overridden / total) / 10.0);
        out.put("perAiClass", perClassOut);
        return out;
    }

    private String extractTopYoloClass(String rawModelOutputJson) {
        if (rawModelOutputJson == null || rawModelOutputJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(rawModelOutputJson);
            JsonNode detections = root.path("yolo").path("detections");
            if (detections.isArray() && !detections.isEmpty()) {
                return detections.get(0).path("class_name").asText("");
            }
        } catch (Exception e) {
            // Malformed/unexpected raw_model_output shape for this row -
            // logged and skipped rather than failing the whole export
            // over one bad row.
            log.warn("Could not parse raw_model_output for feedback export: {}", e.getMessage());
        }
        return "";
    }

    private String csvSafe(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
