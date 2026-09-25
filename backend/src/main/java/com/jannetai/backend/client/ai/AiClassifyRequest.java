package com.jannetai.backend.client.ai;

/**
 * Mirrors ai-service's {@code app/schemas/classify.py: ClassifyRequest}
 * field-for-field (Phase 7 contract, unchanged by Phase 8). Serialized with
 * a snake_case-configured {@link com.fasterxml.jackson.databind.ObjectMapper}
 * inside {@link AiServiceClient} - kept local to this client so the
 * backend's own camelCase REST API (Flutter-facing) is untouched.
 *
 * @param imageBase64      required; the citizen's original BEFORE photo,
 *                         base64-encoded (no data URI prefix)
 * @param description      the complaint's free-text description; ai-service
 *                         uses it as extra context for OCR/Gemini
 * @param priorModelVersion optional; always {@code null} from this backend
 *                         today - there is no re-classification flow yet
 * @param complaintId      included for ai-service's own audit logging
 */
public record AiClassifyRequest(
        String imageBase64,
        String description,
        String priorModelVersion,
        Long complaintId,
        /** Audit GAP-011: platform auto-approve threshold (null = ai-service default). */
        java.math.BigDecimal confidenceThreshold,
        /** Audit GAP-011: active routing-rule thresholds by category name (null/empty = none). */
        java.util.Map<String, java.math.BigDecimal> categoryConfidenceThresholds
) {
    /** Pre-GAP-011 shape: no thresholds, ai-service defaults apply. */
    public AiClassifyRequest(String imageBase64, String description, String priorModelVersion, Long complaintId) {
        this(imageBase64, description, priorModelVersion, complaintId, null, null);
    }
}
