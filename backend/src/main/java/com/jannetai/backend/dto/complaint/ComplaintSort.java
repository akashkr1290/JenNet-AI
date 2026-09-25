package com.jannetai.backend.dto.complaint;

/**
 * Audit GAP-040 (SRS 16.2 Officer Queue: "sortable by status, severity, SLA
 * countdown"): order of GET /api/v1/complaints for staff queues.
 * <ul>
 *   <li>{@link #NEWEST} - newest first (the previous and default behaviour);</li>
 *   <li>{@link #SEVERITY} - Critical, High, Medium, Low, then unrated; within a
 *       severity the SLA due soonest first (no SLA clock last), then newest;</li>
 *   <li>{@link #SLA_DUE} - the persisted SLA deadline (complaints.sla_due_at,
 *       audit GAP-027) soonest first, overdue ones at the top, complaints with
 *       no running SLA clock last; ties by severity, then newest.</li>
 * </ul>
 * Citizens always get NEWEST (their own list has no SLA/severity triage).
 */
public enum ComplaintSort {
    NEWEST,
    SEVERITY,
    SLA_DUE
}
