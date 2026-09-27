package com.jannetai.backend.dto.complaint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jannetai.backend.entity.Prediction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Gap-backlog Patch 30/43 explainability fields, plus (Patch 42, Sep 2026
 * strict recheck) the model's detections as bounding boxes normalised to
 * 0..1 of the original photo, read from the stored raw_model_output.
 * Boxes are only returned when raw_model_output records the coordinate space
 * ("model_input_size", written by ai-service since this recheck); older
 * predictions return an empty list rather than guessing the scale.
 *
 * Pilot request (2026-09-28): {@code decision} explains which stage produced
 * the category - YOLO (with its minimum detection threshold and its best
 * score, even when that score was below the threshold), then Gemini, then
 * manual review - read from raw_model_output["decision"] written by ai-service.
 * Older predictions without that block return {@code decision = null}.
 */
public record AiClassificationResponse(
        BigDecimal confidence,
        String modelVersion,
        boolean duplicateFlagged,
        String aiStatus,
        List<DetectedBox> detections,
        AiDecision decision
) {
    public record DetectedBox(String className, double confidence,
                              double x1, double y1, double x2, double y2) {
    }

    /**
     * @param outcome             MODEL_UNAVAILABLE, YOLO_CONFIRMED_BY_GEMINI, YOLO_GEMINI_DISAGREED,
     *                            GEMINI_REVISED, YOLO_ONLY, GEMINI_VERIFIED, OCR_HINT or MANUAL_REVIEW
     * @param yoloThreshold       minimum YOLO confidence (%) for a detection to count
     * @param yoloPassed          whether YOLO's best box reached {@code yoloThreshold}
     * @param yoloClass           YOLO's best class (also when below the threshold), null if no box at all
     * @param yoloConfidence      YOLO's best confidence (%), null if no box at all
     * @param geminiUsed          whether Gemini answered
     * @param geminiCategory      category Gemini named, if any
     * @param geminiAgrees        Gemini's verdict on YOLO's class (null when there was nothing to compare)
     * @param geminiUnavailableReason why Gemini did not answer (timeout, not configured, ...)
     * @param autoApproveThreshold confidence (%) needed for automatic approval
     * @param autoApproved        whether the complaint skipped manual review
     */
    public record AiDecision(String outcome, double yoloThreshold, boolean yoloPassed,
                             String yoloClass, Double yoloConfidence,
                             boolean geminiUsed, String geminiCategory, Boolean geminiAgrees,
                             String geminiUnavailableReason,
                             double autoApproveThreshold, boolean autoApproved) {
    }

    // Display-only mirror of ai-service's auto_approve_confidence_threshold
    // default (85.0); the real routing decision was made at classification
    // time and is never re-decided here.
    private static final BigDecimal AUTO_APPROVE_THRESHOLD = BigDecimal.valueOf(85);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AiClassificationResponse from(Prediction prediction) {
        String status;
        if (prediction.getAiConfidence() == null) {
            status = "MODEL_UNAVAILABLE";
        } else if (prediction.getAiConfidence().compareTo(AUTO_APPROVE_THRESHOLD) >= 0) {
            status = "AUTO_CLASSIFIED";
        } else {
            status = "MANUAL_REVIEW_REQUIRED";
        }
        return new AiClassificationResponse(
                prediction.getAiConfidence(),
                prediction.getModelVersion(),
                Boolean.TRUE.equals(prediction.getDuplicateFlag()),
                status,
                parseBoxes(prediction.getRawModelOutput()),
                parseDecision(prediction.getRawModelOutput()));
    }

    static AiDecision parseDecision(String rawModelOutputJson) {
        if (rawModelOutputJson == null || rawModelOutputJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = aiOutput(MAPPER.readTree(rawModelOutputJson));
            JsonNode decision = root.path("decision");
            if (!decision.isObject() || !decision.hasNonNull("outcome")) {
                return null; // written before this field existed - nothing to explain
            }
            JsonNode yolo = root.path("yolo");
            JsonNode gemini = root.path("gemini");
            boolean passed = decision.path("yolo_passed_threshold").asBoolean(false);
            JsonNode best = passed ? yolo.path("detections").path(0) : yolo.path("best_below_threshold");
            boolean hasBox = best.isObject() && best.hasNonNull("class_name");
            return new AiDecision(
                    decision.path("outcome").asText(),
                    decision.path("min_detection_threshold").asDouble(yolo.path("min_detection_threshold").asDouble()),
                    passed,
                    hasBox ? best.path("class_name").asText() : null,
                    hasBox ? best.path("confidence").asDouble() : null,
                    gemini.path("used").asBoolean(false),
                    textOrNull(gemini.path("suggested_category")),
                    gemini.path("agrees_with_top_candidate").isBoolean()
                            ? gemini.path("agrees_with_top_candidate").asBoolean() : null,
                    textOrNull(gemini.path("fallback_reason")),
                    decision.path("auto_approve_threshold").asDouble(AUTO_APPROVE_THRESHOLD.doubleValue()),
                    decision.path("auto_approved").asBoolean(false));
        } catch (Exception e) {
            return null; // malformed row: no explanation rather than a wrong one
        }
    }

    /**
     * predictions.raw_model_output is stored by AiClassificationService as
     * {"classify": {..., "raw_model_output": {yolo, gemini, decision, ...}}, "duplicate_check": ...};
     * a bare ai-service raw_model_output (no "classify" wrapper) is accepted too.
     */
    private static JsonNode aiOutput(JsonNode stored) {
        JsonNode nested = stored.path("classify").path("raw_model_output");
        return nested.isObject() ? nested : stored;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    static List<DetectedBox> parseBoxes(String rawModelOutputJson) {
        List<DetectedBox> boxes = new ArrayList<>();
        if (rawModelOutputJson == null || rawModelOutputJson.isBlank()) {
            return boxes;
        }
        try {
            JsonNode yolo = aiOutput(MAPPER.readTree(rawModelOutputJson)).path("yolo");
            JsonNode size = yolo.path("model_input_size");
            if (!size.isArray() || size.size() != 2) {
                return boxes;
            }
            double w = size.get(0).asDouble();
            double h = size.get(1).asDouble();
            if (w <= 0 || h <= 0) {
                return boxes;
            }
            for (JsonNode d : yolo.path("detections")) {
                JsonNode bb = d.path("bounding_box");
                boxes.add(new DetectedBox(
                        d.path("class_name").asText(""),
                        d.path("confidence").asDouble(),
                        clamp(bb.path("x1").asDouble() / w), clamp(bb.path("y1").asDouble() / h),
                        clamp(bb.path("x2").asDouble() / w), clamp(bb.path("y2").asDouble() / h)));
            }
        } catch (Exception e) {
            boxes.clear(); // malformed row: show no boxes rather than wrong ones
        }
        return boxes;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
