package com.jannetai.backend.client.ai;

import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.Severity;

/**
 * Mirrors ai-service's {@code app/schemas/budget_predict.py:
 * BudgetPredictRequest} field-for-field (Phase 10 contract).
 *
 * SRS 15.9 Dependencies: "Priority Prediction Module" - {@code severity}
 * here is always the complaint's FINAL severity (staff override if one
 * was supplied, otherwise the Priority Prediction Module's own output),
 * never computed independently. See
 * {@code service.complaint.PriorityBudgetPredictionService} for the call
 * ordering that guarantees this (priority-predict always precedes
 * budget-predict).
 *
 * @param category    the complaint's classified issue category
 * @param severity    the complaint's final assigned severity (see above)
 * @param wardId      SRS 21.8 Input: "ward/zone" - accepted for audit/
 *                    future-use only, no ward-level historical spend data
 *                    exists yet (see ai-service's schema module docstring);
 *                    may be {@code null} if the complaint's location/ward
 *                    couldn't be resolved
 * @param complaintId included for ai-service's own audit logging
 */
public record AiBudgetPredictRequest(
        ComplaintCategory category,
        Severity severity,
        Long wardId,
        Long complaintId
) {
}
