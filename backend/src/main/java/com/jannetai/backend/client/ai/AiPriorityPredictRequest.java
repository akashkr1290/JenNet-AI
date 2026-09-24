package com.jannetai.backend.client.ai;

import com.jannetai.backend.entity.enums.ComplaintCategory;

/**
 * Mirrors ai-service's {@code app/schemas/priority_predict.py:
 * PriorityPredictRequest} field-for-field (Phase 10 contract).
 *
 * {@code locationFlags} is always sent as {@link AiLocationSensitivityFlags#NONE_AVAILABLE}
 * from this backend today - see {@code service.complaint.PriorityBudgetPredictionService}'s
 * Javadoc for why (no POI/geometry data source exists anywhere in this
 * project's schema, same documented gap as ai-service's own schema module
 * docstring).
 *
 * @param category            the complaint's classified issue category
 * @param corroborationCount  SRS 15.6-fed input; the complaint's current
 *                            {@code corroboration_count} (defaults to 1 -
 *                            see V6 migration - and only increases via
 *                            confirmed duplicate merges)
 * @param locationFlags       see class Javadoc
 * @param complaintId         included for ai-service's own audit logging
 */
public record AiPriorityPredictRequest(
        ComplaintCategory category,
        int corroborationCount,
        AiLocationSensitivityFlags locationFlags,
        Long complaintId
) {
}
