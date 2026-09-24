package com.jannetai.backend.client.ai;

import com.jannetai.backend.entity.enums.ComplaintCategory;

import java.util.List;
import java.util.Map;

/**
 * Mirrors ai-service's {@code app/schemas/classify.py: ClassifyResponse}
 * field-for-field (Phase 7 contract, unchanged by Phase 8).
 *
 * {@code category} deserializes directly onto {@link ComplaintCategory}:
 * ai-service's {@code IssueCategory} enum was deliberately kept
 * value-for-value identical to this one (see that file's own docstring),
 * so no translation table is needed here.
 *
 * {@code preprocessed_image_reference} is always {@code null} in the
 * Phase 7 contract (ai-service has no storage integration) and is not
 * modelled here since Phase 8 never reads it.
 */
public record AiClassifyResult(
        ComplaintCategory category,
        double confidence,
        String geminiDescription,
        String ocrText,
        boolean requiresManualReview,
        String routingReason,
        String modelVersion,
        boolean modelAvailable,
        String imageQualityFlag,
        boolean geminiUsed,
        List<Map<String, Object>> topCandidates,
        Map<String, Object> rawModelOutput
) {
}
