package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.enums.ComplaintStatus;

/**
 * PATCH /api/v1/complaints/{id}/status body (SRS Table 23 + Table 8
 * "Officer Status Update Form" 17.3) - the generic downstream transition
 * action for Officer/Department Head/Admin (Assigned -> In Progress ->
 * Resolved -> Closed, plus Rejected). Phase 12 (Officer Module) change:
 * this is no longer a JSON @RequestBody - the endpoint is now
 * multipart/form-data (mirroring ComplaintController.create's own
 * pattern) so the SRS 17.3 {@code after_photo} field can actually be
 * accepted, closing the gap this record's Phase 6 Javadoc originally
 * flagged as a KNOWN LIMITATION. This record is now just a plain carrier
 * built from the controller's @RequestParam values (no Jakarta Bean
 * Validation annotations - multipart form fields don't support them the
 * same way a JSON body does); ComplaintService.updateStatus performs the
 * SRS Table 8 conditional validation instead (note mandatory, min 10
 * chars, for Resolved/Rejected; after_photo mandatory for Resolved) - see
 * that method's Javadoc.
 */
public record StatusUpdateRequest(
        ComplaintStatus newStatus,
        String note,
        /**
         * Audit GAP-052 (SRS 14.1 step 28: "moves to Rejected with a mandatory
         * reason code"): required when newStatus is REJECTED, ignored otherwise.
         */
        String rejectionReasonCode
) {
    /** Pre-GAP-052 shape (no reason code). */
    public StatusUpdateRequest(ComplaintStatus newStatus, String note) {
        this(newStatus, note, null);
    }
}
