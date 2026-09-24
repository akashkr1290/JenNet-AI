package com.jannetai.backend.client.ai;

import java.math.BigDecimal;

/**
 * Mirrors ai-service's {@code app/schemas/duplicate_check.py:
 * DuplicateCandidate} field-for-field (Phase 9 contract).
 *
 * One existing open complaint that {@code service.complaint.DuplicateDetectionService}
 * supplies for comparison - ai-service never looks candidates up itself
 * (it is stateless with respect to business data, ARCHITECTURE.md Section
 * 2.3); this backend owns building the candidate list.
 *
 * @param complaintId     the candidate complaint's ID - echoed back as
 *                        {@code parent_complaint_id} if it turns out to be
 *                        the match
 * @param referenceNumber included only so ai-service's audit log/raw_output
 *                        is human-readable; never used for matching logic
 * @param imageBase64     the candidate's own BEFORE photo, base64-encoded -
 *                        same image_base64 convention as
 *                        {@link AiClassifyRequest}
 * @param latitude        candidate's resolved location; may be {@code null}
 *                        only defensively - V5's schema constraint makes
 *                        this practically unreachable, see
 *                        DuplicateDetectionService's Javadoc
 * @param longitude       see {@code latitude}
 * @param createdAt       candidate's {@code created_at}, ISO-8601
 *                        ({@code LocalDateTime.toString()}, e.g.
 *                        {@code 2026-08-01T10:15:30}), for ai-service's
 *                        30-day time-window filter (SRS 15.6). DECISION:
 *                        sent as a plain string, not a typed timestamp -
 *                        {@link AiServiceClient}'s ai-service-facing
 *                        {@code ObjectMapper} is deliberately minimal (no
 *                        JSR-310 module registered, see that class's
 *                        Javadoc), and this backend's own
 *                        {@code LocalDateTime} columns carry no timezone
 *                        anyway. ai-service's pydantic {@code datetime}
 *                        field parses a naive ISO string like this
 *                        directly and treats it as UTC - see
 *                        {@code duplicate_service.py}'s
 *                        {@code score_candidate} for the matching
 *                        naive-timestamp-is-UTC assumption on that side.
 */
public record AiDuplicateCandidate(
        Long complaintId,
        String referenceNumber,
        String imageBase64,
        BigDecimal latitude,
        BigDecimal longitude,
        String createdAt
) {
}
