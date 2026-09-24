package com.jannetai.backend.entity.enums;

/**
 * Complaint lifecycle status - the 12-value list LOCKED in Phase 1
 * (ARCHITECTURE.md Section 4 / PROJECT_INTEGRATION.md Section 3). Matches
 * complaints.status and status_history.new_status/previous_status CHECK
 * constraints exactly (V6, V10).
 *
 * Per V6's header comment, ESCALATED/REOPENED are kept as status values here
 * even though the SRS treats escalation/reopen as annotations on the current
 * status rather than replacement states; complaints.is_escalated/is_reopened
 * carry that alternate semantics. Phase 6 (Complaint Module) owns the real
 * transition table and may retire whichever representation goes unused.
 */
public enum ComplaintStatus {
    DRAFT,
    SUBMITTED,
    AI_PROCESSING,
    VERIFIED,
    ASSIGNED,
    IN_PROGRESS,
    RESOLVED,
    CLOSED,
    DUPLICATE,
    REJECTED,
    ESCALATED,
    REOPENED
}
