package com.jannetai.backend.dto.complaint;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * PATCH /api/v1/complaints/{id}/assign body - Phase 11's dedicated manual
 * reassignment action (SRS 15.7 Features: "manual reassignment by Admin
 * or Department Head"). Deliberately separate from the generic
 * PATCH .../status action: reassignment changes department_id/
 * assigned_officer_id directly and is not itself a
 * {@link com.jannetai.backend.entity.enums.ComplaintStatus} transition -
 * a complaint already ASSIGNED can be moved to a different department or
 * handed to a different officer without its status changing at all.
 *
 * {@code departmentId} is required (a reassignment must always name a
 * target department); {@code officerId} is optional - a Department Head
 * reassigning within their own department, or leaving a complaint
 * unassigned to an individual officer (SRS 15.7 Exceptions: "the
 * complaint remains 'Assigned' at department level"), both pass a null
 * officerId.
 */
public record AssignmentRequest(
        @NotNull
        Long departmentId,

        Long officerId,

        @Size(max = 500)
        String note
) {
}
