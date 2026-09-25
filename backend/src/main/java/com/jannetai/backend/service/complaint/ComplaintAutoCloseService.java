package com.jannetai.backend.service.complaint;

import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.enums.ActorType;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.service.AuditJson;
import com.jannetai.backend.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Audit GAP-028 (SRS 14.1 step 27: "If no citizen action is taken within a
 * configurable grace period (default 7 days) ... the complaint transitions to
 * Closed"; workflow table: "automatic closure after the grace period elapses").
 * Previously only the citizen's explicit confirmation closed a complaint, so
 * complaints stayed RESOLVED forever.
 *
 * <p>Uses the same {@code app.complaint.reopen-grace-period-days} and the same
 * "resolved at" instant as the citizen reopen check, so a complaint can be
 * reopened exactly until it is auto-closed. The transition goes through
 * ComplaintService.recordHistory (status history with actor SYSTEM, the SLA
 * hook and the citizen's CLOSED notification) plus an audit entry.
 */
@Service
public class ComplaintAutoCloseService {

    private static final Logger log = LoggerFactory.getLogger(ComplaintAutoCloseService.class);

    private final ComplaintRepository complaintRepository;
    private final ComplaintService complaintService;
    private final AuditService auditService;

    @Value("${app.complaint.reopen-grace-period-days}")
    private int gracePeriodDays;

    @Value("${app.complaint.auto-close-batch-size:200}")
    private int batchSize = 200;

    public ComplaintAutoCloseService(ComplaintRepository complaintRepository, ComplaintService complaintService,
                                     AuditService auditService) {
        this.complaintRepository = complaintRepository;
        this.complaintService = complaintService;
        this.auditService = auditService;
    }

    @Scheduled(fixedDelayString = "${app.complaint.auto-close-interval-ms:3600000}",
            initialDelayString = "${app.complaint.auto-close-initial-delay-ms:60000}")
    @Transactional
    public int closeExpiredResolvedComplaints() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(gracePeriodDays);
        List<Complaint> expired = complaintRepository.findResolvedPastGracePeriod(cutoff, PageRequest.of(0, batchSize));
        for (Complaint complaint : expired) {
            ComplaintStateMachine.assertSystemTransitionAllowed(ComplaintStatus.RESOLVED, ComplaintStatus.CLOSED);
            complaint.setStatus(ComplaintStatus.CLOSED);
            complaintRepository.save(complaint);
            complaintService.recordHistory(complaint, ComplaintStatus.RESOLVED, ComplaintStatus.CLOSED, null,
                    ActorType.SYSTEM, "Automatically closed: no citizen action within the " + gracePeriodDays
                            + "-day grace period");
            auditService.record(null, "COMPLAINT_AUTO_CLOSED", "COMPLAINT", complaint.getComplaintId(),
                    AuditJson.of("grace_period_days", gracePeriodDays));
        }
        if (!expired.isEmpty()) {
            log.info("Auto-closed {} resolved complaint(s) after the {}-day grace period", expired.size(), gracePeriodDays);
        }
        return expired.size();
    }
}
