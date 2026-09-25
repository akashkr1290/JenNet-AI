package com.jannetai.backend.service.admin;

import com.jannetai.backend.dto.admin.OutOfJurisdictionComplaintResponse;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Location;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.LocationRepository;
import com.jannetai.backend.service.AuditJson;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.WardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/**
 * Audit GAP-031 (SRS 15.5 Exceptions: "coordinates outside the configured
 * boundary are flagged 'out of jurisdiction' for Admin review"). The flag has
 * been set by LocationService since Phase 6; nothing surfaced it until now.
 *
 * <ul>
 *   <li>{@link #list} - open complaints whose location is flagged (closed and
 *       rejected complaints need no review);</li>
 *   <li>{@link #accept} - the Admin confirms the complaint belongs to the
 *       municipality and assigns the ward: the location gets that ward and the
 *       flag is cleared (audited, with the coordinates).</li>
 * </ul>
 * A complaint that genuinely belongs elsewhere is rejected through the normal
 * verification/status flow (with a rejection reason), not here, so the
 * citizen is notified the usual way.
 */
@Service
@RequiredArgsConstructor
public class OutOfJurisdictionReviewService {

    static final Set<ComplaintStatus> NOT_REVIEWABLE = Set.of(
            ComplaintStatus.CLOSED, ComplaintStatus.REJECTED, ComplaintStatus.DUPLICATE);

    private final ComplaintRepository complaintRepository;
    private final LocationRepository locationRepository;
    private final WardService wardService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<OutOfJurisdictionComplaintResponse> list(int page, int pageSize) {
        return complaintRepository.findOutOfJurisdiction(NOT_REVIEWABLE,
                        PageRequest.of(Math.max(page, 0), Math.min(Math.max(pageSize, 1), 100)))
                .map(OutOfJurisdictionComplaintResponse::from);
    }

    @Transactional
    public OutOfJurisdictionComplaintResponse accept(User admin, Long complaintId, Long wardId) {
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found: " + complaintId));
        Location location = complaint.getLocation();
        if (location == null || !Boolean.TRUE.equals(location.getOutOfJurisdiction())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This complaint is not flagged as out of jurisdiction");
        }
        Ward ward = wardService.requireActiveWardEntity(wardId);
        Long previousWard = location.getWard() != null ? location.getWard().getWardId() : null;
        location.setWard(ward);
        location.setOutOfJurisdiction(false);
        locationRepository.save(location);
        auditService.record(admin, "OUT_OF_JURISDICTION_ACCEPTED", "COMPLAINT", complaintId, AuditJson.of(
                "ward_id", ward.getWardId(), "ward_name", ward.getName(), "previous_ward_id", previousWard,
                "latitude", location.getLatitude(), "longitude", location.getLongitude()));
        return OutOfJurisdictionComplaintResponse.from(complaint);
    }
}
