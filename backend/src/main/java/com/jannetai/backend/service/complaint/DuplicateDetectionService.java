package com.jannetai.backend.service.complaint;

import com.jannetai.backend.client.ai.AiDuplicateCandidate;
import com.jannetai.backend.client.ai.AiDuplicateCheckRequest;
import com.jannetai.backend.client.ai.AiDuplicateCheckResult;
import com.jannetai.backend.client.ai.AiServiceCallException;
import com.jannetai.backend.client.ai.AiServiceClient;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Image;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.ImageType;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.ImageRepository;
import com.jannetai.backend.storage.StorageException;
import com.jannetai.backend.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 9 (Duplicate Detection Module, SRS 15.6/21.5). Builds the
 * candidate pool ai-service's {@code /duplicate-check} needs and calls it
 * - the actual routing decision (auto-merge vs. manual review vs.
 * proceed-normally) lives in {@link AiClassificationService}, which calls
 * this class, not here; this class's only job is "gather + call ai-service
 * + hand back its verdict".
 *
 * CANDIDATE POOL (SRS 15.6 Inputs: "existing open complaints in the same
 * ward"):
 * <ul>
 *   <li>Same ward as the new complaint's resolved location
 *       ({@link ComplaintRepository#findDuplicateCandidates}).</li>
 *   <li>"Open" status - DECISION: {@link #OPEN_STATUSES} below (SUBMITTED,
 *       AI_PROCESSING, VERIFIED, ASSIGNED, IN_PROGRESS). Excludes DRAFT
 *       (never persisted, Phase 6), REJECTED/DUPLICATE (terminal, not
 *       "open" issues needing dedup), and RESOLVED/CLOSED (the underlying
 *       issue is understood to already be fixed, so a new report of the
 *       same spot is a fresh issue, not a duplicate of a closed one - not
 *       an SRS-literal rule, but the only reading of "open" that makes the
 *       corroboration-count business purpose - avoiding redundant *active*
 *       tickets - coherent). ESCALATED/REOPENED never appear in
 *       {@code status} at all (Phase 6 annotation decision,
 *       ComplaintStateMachine's own Javadoc), so they're not listed.</li>
 *   <li>Within the last {@code app.duplicate-detection.candidate-window-days}
 *       days (matches SRS 15.6's 30-day window - a cheap pre-filter here so
 *       this backend doesn't even fetch/send candidates ai-service would
 *       reject as too old anyway).</li>
 *   <li>Capped to {@code app.duplicate-detection.max-candidates} most
 *       recent, so a busy ward can't balloon one request into dozens of
 *       image loads/comparisons.</li>
 *   <li>Excludes the complaint being checked itself.</li>
 * </ul>
 *
 * KNOWN LIMITATION (documented, not fixed this phase): if the new
 * complaint's own {@code Location.ward} is unresolved (null - possible per
 * {@link LocationService}'s Javadoc, no real geocoding provider is
 * integrated), no candidate lookup is possible and this returns
 * {@code null} (no duplicate check performed at all, not an error) -
 * {@link AiClassificationService} treats that exactly like "no duplicate
 * found" and proceeds with classify-only routing. A future phase could
 * widen this to a lat/lon bounding-box fallback when ward is unset; not
 * built now (no demonstrated need, ARCHITECTURE.md Section 7).
 *
 * KNOWN LIMITATION: a candidate whose own stored photo can't be loaded
 * (missing on disk, storage error) is skipped, not treated as a hard
 * failure - logged, and the duplicate check proceeds with whatever
 * candidates did load successfully. Mirrors ai-service's own per-candidate
 * resilience for a bad image_url/image_base64 (routes/duplicate_check.py).
 */
@Service
@RequiredArgsConstructor
public class DuplicateDetectionService {

    private static final Logger log = LoggerFactory.getLogger(DuplicateDetectionService.class);

    /** See class Javadoc "CANDIDATE POOL" for the full reasoning. */
    private static final Set<ComplaintStatus> OPEN_STATUSES = EnumSet.of(
            ComplaintStatus.SUBMITTED, ComplaintStatus.AI_PROCESSING, ComplaintStatus.VERIFIED,
            ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS);

    private final ComplaintRepository complaintRepository;
    private final ImageRepository imageRepository;
    private final StorageService storageService;
    private final AiServiceClient aiServiceClient;

    @Value("${app.duplicate-detection.candidate-window-days}")
    private int candidateWindowDays;

    @Value("${app.duplicate-detection.max-candidates}")
    private int maxCandidates;

    /**
     * @return ai-service's verdict, or {@code null} if no duplicate check
     *         could be attempted (ward unresolved - see class Javadoc);
     *         {@code null} is a valid, non-error outcome the caller should
     *         treat as "not a duplicate".
     * @throws AiServiceCallException if ai-service itself couldn't be
     *                                 reached/errored - the caller decides
     *                                 how to handle that (see
     *                                 AiClassificationService), this class
     *                                 does not swallow it.
     */
    public AiDuplicateCheckResult check(Complaint complaint) {
        Long wardId = (complaint.getLocation() != null && complaint.getLocation().getWard() != null)
                ? complaint.getLocation().getWard().getWardId() : null;
        if (wardId == null) {
            log.debug("Complaint {} has no resolved ward; skipping duplicate check (see class Javadoc)",
                    complaint.getComplaintId());
            return null;
        }

        LocalDateTime since = LocalDateTime.now().minusDays(candidateWindowDays);
        Pageable pageable = PageRequest.of(0, Math.max(maxCandidates, 1), Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Complaint> candidateComplaints = complaintRepository.findDuplicateCandidates(
                wardId, complaint.getComplaintId(), OPEN_STATUSES, since, pageable);

        List<AiDuplicateCandidate> candidates = candidateComplaints.stream()
                .map(this::toCandidateOrNull)
                .filter(java.util.Objects::nonNull)
                .toList();

        Image ownPhoto = imageRepository
                .findFirstByComplaint_ComplaintIdAndImageTypeOrderByUploadedAtAsc(
                        complaint.getComplaintId(), ImageType.BEFORE)
                .orElseThrow(() -> new AiServiceCallException("NO_IMAGE_ON_FILE",
                        "Complaint " + complaint.getComplaintId() + " has no BEFORE photo to duplicate-check"));
        String ownImageBase64 = Base64.getEncoder().encodeToString(storageService.load(ownPhoto.getStorageKey()));

        AiDuplicateCheckRequest request = new AiDuplicateCheckRequest(
                complaint.getComplaintId(),
                ownImageBase64,
                preciseLatitude(complaint.getLocation()),
                preciseLongitude(complaint.getLocation()),
                candidates);

        return aiServiceClient.checkDuplicate(request);
    }

    /**
     * Remaining-gaps item 3: an approximate (WARD_FALLBACK) point must never be
     * used for 50 m proximity matching - every such complaint in a ward shares
     * one point and would look co-located. Sending no coordinates makes
     * ai-service apply SRS 15.6's own "GPS unavailable" rule instead (raised
     * similarity threshold, proximity not evaluated).
     */
    private static java.math.BigDecimal preciseLatitude(com.jannetai.backend.entity.Location location) {
        return (location == null || location.getSource() == com.jannetai.backend.entity.enums.LocationSource.WARD_FALLBACK)
                ? null : location.getLatitude();
    }

    private static java.math.BigDecimal preciseLongitude(com.jannetai.backend.entity.Location location) {
        return (location == null || location.getSource() == com.jannetai.backend.entity.enums.LocationSource.WARD_FALLBACK)
                ? null : location.getLongitude();
    }

    /** Returns null (skip this one candidate) rather than throwing, per class Javadoc's per-candidate resilience. */
    private AiDuplicateCandidate toCandidateOrNull(Complaint candidate) {
        try {
            Image photo = imageRepository
                    .findFirstByComplaint_ComplaintIdAndImageTypeOrderByUploadedAtAsc(
                            candidate.getComplaintId(), ImageType.BEFORE)
                    .orElse(null);
            if (photo == null) {
                log.debug("Duplicate-check candidate {} has no BEFORE photo; skipping", candidate.getComplaintId());
                return null;
            }
            byte[] bytes = storageService.load(photo.getStorageKey());
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return new AiDuplicateCandidate(
                    candidate.getComplaintId(),
                    candidate.getReferenceNumber(),
                    base64,
                    preciseLatitude(candidate.getLocation()),
                    preciseLongitude(candidate.getLocation()),
                    candidate.getCreatedAt().toString());
        } catch (StorageException e) {
            log.warn("Could not load photo for duplicate-check candidate {}: {}",
                    candidate.getComplaintId(), e.getMessage());
            return null;
        }
    }
}
