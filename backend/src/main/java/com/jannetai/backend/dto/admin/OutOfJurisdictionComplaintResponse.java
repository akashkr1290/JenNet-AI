package com.jannetai.backend.dto.admin;

import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Location;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.LocationSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Audit GAP-031: one row of the Admin out-of-jurisdiction review queue (SRS 15.5). */
public record OutOfJurisdictionComplaintResponse(
        Long complaintId,
        String referenceNumber,
        ComplaintStatus status,
        ComplaintCategory category,
        BigDecimal latitude,
        BigDecimal longitude,
        LocationSource locationSource,
        Long wardId,
        String wardName,
        LocalDateTime createdAt
) {
    public static OutOfJurisdictionComplaintResponse from(Complaint c) {
        Location l = c.getLocation();
        return new OutOfJurisdictionComplaintResponse(
                c.getComplaintId(), c.getReferenceNumber(), c.getStatus(), c.getCategory(),
                l.getLatitude(), l.getLongitude(), l.getSource(),
                l.getWard() != null ? l.getWard().getWardId() : null,
                l.getWard() != null ? l.getWard().getName() : null,
                c.getCreatedAt());
    }
}
