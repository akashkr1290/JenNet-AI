package com.jannetai.backend.entity.enums;

/**
 * Phase 6: the three outcomes the manual Verification Team override
 * (PATCH /api/v1/complaints/{id}/verify) can record for a complaint
 * currently in AI_PROCESSING - standing in for the AI Analysis Module's
 * confidence-check decision point (SRS Table 10 / 15.4) until the real AI
 * service exists (Phase 7). Not a database column value itself; each
 * decision maps onto the corresponding {@link ComplaintStatus} transition
 * inside ComplaintStateMachine/ComplaintService.
 */
public enum VerificationDecision {
    VERIFIED,
    REJECTED,
    DUPLICATE
}
