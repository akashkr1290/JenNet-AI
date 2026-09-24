package com.jannetai.backend.client.ai;

import com.jannetai.backend.entity.enums.Severity;

import java.util.Map;

/**
 * Mirrors ai-service's {@code app/schemas/priority_predict.py:
 * PriorityPredictResponse} field-for-field (Phase 10 contract).
 *
 * {@code severity}/{@code priorityScore} are SRS 20.3 Table 24's literal
 * output fields; the rest are the same kind of documented addition Phase
 * 7-9 made for their own {@code requires_manual_review}/{@code match_tier}
 * fields - see ai-service's schema module for the full reasoning.
 */
public record AiPriorityPredictResult(
        Severity severity,
        double priorityScore,
        boolean safetyHazardOverrideApplied,
        boolean corroborationBumpApplied,
        Severity baseSeverity,
        String modelVersion,
        Map<String, Object> rawOutput
) {
}
