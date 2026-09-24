package com.jannetai.backend.client.ai;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Mirrors ai-service's {@code app/schemas/budget_predict.py:
 * BudgetPredictResponse} field-for-field (Phase 10 contract).
 *
 * {@code estimatedCostMin}/{@code estimatedCostMax}/
 * {@code estimatedResolutionDays}/{@code confidence} are SRS 20.3 Table
 * 24's literal output fields, and map directly onto
 * {@code budget.estimated_cost_min}/{@code estimated_cost_max}/
 * {@code estimated_resolution_days}/{@code confidence_level} (V9
 * migration) - {@code confidence} is always {@code "PRELIMINARY"} this
 * phase (see ai-service's {@code budget_service.py} module docstring;
 * matches {@code budget.confidence_level}'s own DB default).
 */
public record AiBudgetPredictResult(
        BigDecimal estimatedCostMin,
        BigDecimal estimatedCostMax,
        int estimatedResolutionDays,
        String confidence,
        String modelVersion,
        boolean guardrailApplied,
        Map<String, Object> rawOutput
) {
}
