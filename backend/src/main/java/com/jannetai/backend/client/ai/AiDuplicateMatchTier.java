package com.jannetai.backend.client.ai;

/**
 * Mirrors ai-service's {@code app/schemas/duplicate_check.py: MatchTier}
 * (Phase 9 contract) - the SRS 21.5 Confidence Score bands.
 */
public enum AiDuplicateMatchTier {
    AUTO_MERGE,
    MANUAL_REVIEW,
    NOT_DUPLICATE,
    NO_CANDIDATES
}
