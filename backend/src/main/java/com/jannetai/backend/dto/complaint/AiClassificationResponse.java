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
 */
public record AiClassificationResponse(
        BigDecimal confidence,
        String modelVersion,
        boolean duplicateFlagged,
        String aiStatus,
        List<DetectedBox> detections
) {
    public record DetectedBox(String className, double confidence,
                              double x1, double y1, double x2, double y2) {
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
                parseBoxes(prediction.getRawModelOutput()));
    }

    private static List<DetectedBox> parseBoxes(String rawModelOutputJson) {
        List<DetectedBox> boxes = new ArrayList<>();
        if (rawModelOutputJson == null || rawModelOutputJson.isBlank()) {
            return boxes;
        }
        try {
            JsonNode yolo = MAPPER.readTree(rawModelOutputJson).path("yolo");
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
