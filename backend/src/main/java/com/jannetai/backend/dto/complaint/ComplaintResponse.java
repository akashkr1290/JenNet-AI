package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Severity;

import java.time.LocalDateTime;
import java.util.List;

/** Full detail view - GET /api/v1/complaints/{id} and the response of create/verify/reopen/status actions. */
public record ComplaintResponse(
        Long complaintId,
        String referenceNumber,
        Long citizenId,
        ComplaintCategory category,
        String description,
        LocationResponse location,
        Long departmentId,
        Long assignedOfficerId,
        ComplaintStatus status,
        Severity severity,
        Long parentComplaintId,
        Integer corroborationCount,
        Boolean isEscalated,
        LocalDateTime escalatedAt,
        Boolean isReopened,
        LocalDateTime reopenedAt,
        String rejectionReasonCode,
        List<ImageResponse> images,
        List<StatusHistoryResponse> statusHistory,
        List<InternalNoteResponse> internalNotes,
        BudgetResponse budget,
        AiClassificationResponse aiClassification,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * Phase 12: {@code internalNotes} defaults to an empty list via this
     * overload - kept for the handful of existing call sites (create/
     * reopen/etc.) that build a response for a CITIZEN-facing action and
     * must never include staff-only internal notes. Staff-facing call
     * sites (getDetail/updateStatus/etc. for non-citizen roles) use the
     * 4-arg overload below instead. See ComplaintService.toResponse.
     */
    public static ComplaintResponse from(Complaint c, List<ImageResponse> images,
                                          List<StatusHistoryResponse> statusHistory) {
        return from(c, images, statusHistory, List.of(), null, null);
    }

    public static ComplaintResponse from(Complaint c, List<ImageResponse> images,
                                          List<StatusHistoryResponse> statusHistory,
                                          List<InternalNoteResponse> internalNotes,
                                          BudgetResponse budget,
                                          AiClassificationResponse aiClassification) {
        return new ComplaintResponse(
                c.getComplaintId(),
                c.getReferenceNumber(),
                c.getCitizen().getUserId(),
                c.getCategory(),
                c.getDescription(),
                c.getLocation() != null ? LocationResponse.from(c.getLocation()) : null,
                c.getDepartment() != null ? c.getDepartment().getDepartmentId() : null,
                c.getAssignedOfficer() != null ? c.getAssignedOfficer().getUserId() : null,
                c.getStatus(),
                c.getSeverity(),
                c.getParentComplaint() != null ? c.getParentComplaint().getComplaintId() : null,
                c.getCorroborationCount(),
                c.getIsEscalated(),
                c.getEscalatedAt(),
                c.getIsReopened(),
                c.getReopenedAt(),
                c.getRejectionReasonCode(),
                images,
                statusHistory,
                internalNotes,
                budget,
                aiClassification,
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
