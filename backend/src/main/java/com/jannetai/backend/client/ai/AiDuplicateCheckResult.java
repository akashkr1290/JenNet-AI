package com.jannetai.backend.client.ai;

import java.util.List;
import java.util.Map;

/**
 * Mirrors ai-service's {@code app/schemas/duplicate_check.py:
 * DuplicateCheckResponse} field-for-field (Phase 9 contract).
 *
 * {@code isDuplicate}/{@code parentComplaintId}/{@code similarityScore}
 * are SRS 20.3's literal contract fields; the rest are the same kind of
 * documented addition Phase 7/8 made for
 * {@link AiClassifyResult#requiresManualReview()} - see
 * ai-service's schema module for the full reasoning.
 */
public record AiDuplicateCheckResult(
        boolean isDuplicate,
        Long parentComplaintId,
        double similarityScore,
        boolean requiresManualReview,
        AiDuplicateMatchTier matchTier,
        double thresholdUsed,
        boolean gpsAvailable,
        String modelVersion,
        List<Map<String, Object>> topMatches,
        Map<String, Object> rawOutput
) {
}
