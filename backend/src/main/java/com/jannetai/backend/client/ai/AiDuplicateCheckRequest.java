package com.jannetai.backend.client.ai;

import java.math.BigDecimal;
import java.util.List;

/**
 * Mirrors ai-service's {@code app/schemas/duplicate_check.py:
 * DuplicateCheckRequest} field-for-field (Phase 9 contract).
 *
 * {@code candidates} is the documented addition beyond SRS 20.3's literal
 * {@code {complaint_id, image_embedding, latitude, longitude}} body - see
 * ai-service's {@code duplicate_check.py} module docstring for the full
 * reasoning (ai-service is stateless with respect to business data, so the
 * caller that owns the complaint record must supply the candidate set).
 *
 * @param complaintId  the new submission being checked
 * @param imageBase64  the new submission's own BEFORE photo, base64-encoded
 * @param latitude     the new submission's resolved location
 * @param longitude    see {@code latitude}
 * @param candidates   existing open complaints (same ward, within the
 *                     30-day window) to compare against - see
 *                     {@code service.complaint.DuplicateDetectionService}
 *                     for how this list is built; empty is valid (no
 *                     candidates yet)
 */
public record AiDuplicateCheckRequest(
        Long complaintId,
        String imageBase64,
        BigDecimal latitude,
        BigDecimal longitude,
        List<AiDuplicateCandidate> candidates
) {
}
