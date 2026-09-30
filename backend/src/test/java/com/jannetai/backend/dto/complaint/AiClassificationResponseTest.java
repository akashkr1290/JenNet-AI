package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.Prediction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pilot request (2026-09-28): the AI decision path (YOLO threshold -> Gemini ->
 * manual review) is read from predictions.raw_model_output, which
 * AiClassificationService stores as {"classify": {"raw_model_output": {...}}}.
 */
class AiClassificationResponseTest {

    private static String stored(String aiRawModelOutput) {
        return "{\"classify\":{\"category\":\"OPEN_MANHOLE\",\"raw_model_output\":" + aiRawModelOutput
                + "},\"duplicate_check\":{\"attempted\":false}}";
    }

    private static final String GEMINI_VERIFIED = stored("""
            {"yolo":{"model_available":true,"detections":[],"min_detection_threshold":50.0,
                     "best_below_threshold":{"class_name":"open_manhole","confidence":40.2,
                        "bounding_box":{"x1":10,"y1":20,"x2":30,"y2":40}},
                     "model_input_size":[100,200]},
             "gemini":{"used":true,"agrees_with_top_candidate":null,"fallback_reason":null,
                       "suggested_category":"OPEN_MANHOLE"},
             "decision":{"outcome":"GEMINI_VERIFIED","routing_reason":"GEMINI_CATEGORY_NO_DETECTION",
                         "final_category":"OPEN_MANHOLE","yolo_passed_threshold":false,
                         "min_detection_threshold":50.0,"auto_approve_threshold":85.0,"auto_approved":false}}
            """);

    private static final String YOLO_CONFIRMED = stored("""
            {"yolo":{"model_available":true,"min_detection_threshold":50.0,"best_below_threshold":null,
                     "detections":[{"class_name":"pothole","confidence":75.3,
                        "bounding_box":{"x1":50,"y1":100,"x2":100,"y2":200}}],
                     "model_input_size":[100,200]},
             "gemini":{"used":true,"agrees_with_top_candidate":true,"fallback_reason":null,
                       "suggested_category":"POTHOLE"},
             "decision":{"outcome":"YOLO_CONFIRMED_BY_GEMINI","yolo_passed_threshold":true,
                         "min_detection_threshold":50.0,"auto_approve_threshold":85.0,"auto_approved":false}}
            """);

    @Test
    void yoloBelowThresholdThenGeminiVerified() {
        AiClassificationResponse.AiDecision d = AiClassificationResponse.parseDecision(GEMINI_VERIFIED);
        assertThat(d.outcome()).isEqualTo("GEMINI_VERIFIED");
        assertThat(d.yoloThreshold()).isEqualTo(50.0);
        assertThat(d.yoloPassed()).isFalse();
        assertThat(d.yoloClass()).isEqualTo("open_manhole");
        assertThat(d.yoloConfidence()).isEqualTo(40.2);
        assertThat(d.geminiUsed()).isTrue();
        assertThat(d.geminiCategory()).isEqualTo("OPEN_MANHOLE");
        assertThat(d.geminiAgrees()).isNull();
        assertThat(d.autoApproveThreshold()).isEqualTo(85.0);
        assertThat(d.autoApproved()).isFalse();
        // a below-threshold guess is never drawn as a detection box
        assertThat(AiClassificationResponse.parseBoxes(GEMINI_VERIFIED)).isEmpty();
    }

    @Test
    void yoloPassedAndGeminiAgreed() {
        AiClassificationResponse.AiDecision d = AiClassificationResponse.parseDecision(YOLO_CONFIRMED);
        assertThat(d.outcome()).isEqualTo("YOLO_CONFIRMED_BY_GEMINI");
        assertThat(d.yoloPassed()).isTrue();
        assertThat(d.yoloClass()).isEqualTo("pothole");
        assertThat(d.yoloConfidence()).isEqualTo(75.3);
        assertThat(d.geminiAgrees()).isTrue();
    }

    @Test
    void boxesAreReadFromTheStoredShape() {
        var boxes = AiClassificationResponse.parseBoxes(YOLO_CONFIRMED);
        assertThat(boxes).hasSize(1);
        assertThat(boxes.get(0).className()).isEqualTo("pothole");
        assertThat(boxes.get(0).x1()).isEqualTo(0.5);
        assertThat(boxes.get(0).y2()).isEqualTo(1.0);
    }

    @Test
    void bothFailedMeansManualReviewWithGeminiReason() {
        String json = stored("""
                {"yolo":{"model_available":true,"detections":[],"min_detection_threshold":50.0,
                         "best_below_threshold":null},
                 "gemini":{"used":false,"agrees_with_top_candidate":null,"fallback_reason":"timeout",
                           "suggested_category":null},
                 "decision":{"outcome":"MANUAL_REVIEW","yolo_passed_threshold":false,
                             "min_detection_threshold":50.0,"auto_approve_threshold":85.0,"auto_approved":false}}
                """);
        AiClassificationResponse.AiDecision d = AiClassificationResponse.parseDecision(json);
        assertThat(d.outcome()).isEqualTo("MANUAL_REVIEW");
        assertThat(d.yoloClass()).isNull();
        assertThat(d.yoloConfidence()).isNull();
        assertThat(d.geminiUsed()).isFalse();
        assertThat(d.geminiUnavailableReason()).isEqualTo("timeout");
    }

    @Test
    void olderRowsWithoutADecisionHaveNoExplanation() {
        String old = stored("{\"yolo\":{\"detections\":[],\"min_detection_threshold\":50.0}}");
        assertThat(AiClassificationResponse.parseDecision(old)).isNull();
        assertThat(AiClassificationResponse.parseDecision(null)).isNull();
        assertThat(AiClassificationResponse.parseDecision("not json")).isNull();
        assertThat(AiClassificationResponse.parseBoxes("not json")).isEmpty();
    }

    @Test
    void bareAiOutputWithoutWrapperIsAlsoRead() {
        String bare = YOLO_CONFIRMED.substring(YOLO_CONFIRMED.indexOf("\"raw_model_output\":") + 19,
                YOLO_CONFIRMED.lastIndexOf("},\"duplicate_check\""));
        assertThat(AiClassificationResponse.parseDecision(bare).outcome()).isEqualTo("YOLO_CONFIRMED_BY_GEMINI");
        assertThat(AiClassificationResponse.parseBoxes(bare)).hasSize(1);
    }

    @Test
    void fromPredictionCarriesTheDecision() {
        Prediction p = Prediction.builder().aiConfidence(BigDecimal.ZERO).modelVersion("yolov11-civic-v1.1")
                .duplicateFlag(false).rawModelOutput(GEMINI_VERIFIED).build();
        AiClassificationResponse r = AiClassificationResponse.from(p);
        assertThat(r.decision().outcome()).isEqualTo("GEMINI_VERIFIED");
        assertThat(r.aiStatus()).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(r.detections()).isEmpty();
    }

    @Test
    void aiStatusFollowsTheRecordedDecisionNotAFixedThreshold() {
        // Pilot 2026-09-30: threshold 50 - a 60% detection accepted at classification
        // time must show as auto-classified.
        String accepted = YOLO_CONFIRMED.replace("\"auto_approve_threshold\":85.0,\"auto_approved\":false",
                "\"auto_approve_threshold\":50.0,\"auto_approved\":true");
        Prediction p = Prediction.builder().aiConfidence(new BigDecimal("60.00")).modelVersion("yolov11-civic-v1.1")
                .duplicateFlag(false).rawModelOutput(accepted).build();
        AiClassificationResponse r = AiClassificationResponse.from(p);
        assertThat(r.decision().autoApproved()).isTrue();
        assertThat(r.decision().autoApproveThreshold()).isEqualTo(50.0);
        assertThat(r.aiStatus()).isEqualTo("AUTO_CLASSIFIED");
    }
}
