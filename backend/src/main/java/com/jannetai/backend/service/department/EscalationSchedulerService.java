package com.jannetai.backend.service.department;

import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Severity;
import com.jannetai.backend.repository.AuditLogRepository;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.admin.PlatformSettingKey;
import com.jannetai.backend.service.admin.PlatformSettingsService;
import com.jannetai.backend.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Phase 11 (SRS 15.7 Features: "escalation routing on SLA breach"; SRS
 * 14.3's concrete per-severity SLA timings: Critical 24h, High 72h,
 * Medium 7 days, Low 14 days).
 *
 * ARCHITECTURE DECISION - severity-based SLA, not routing_rules.sla_hours:
 * {@link com.jannetai.backend.entity.RoutingRule#getSlaHours()} exists
 * and is per-category, but SRS 14.3's literal escalation business rule is
 * per-severity, not per-category - the two fields represent a genuine
 * pre-existing tension in the schema (V14 was designed in Phase 2, before
 * this module's exact escalation rule was worked out). This class
 * therefore reads severity-keyed thresholds from
 * {@code app.escalation.sla-hours.*} (configurable, defaults matching SRS
 * 14.3's literal numbers) rather than routing_rules.sla_hours - documented
 * in PROJECT_INTEGRATION.md Section 6 rather than silently picking one.
 *
 * PHASE 14 ADDITION: SRS 15.6 explicitly says these four thresholds are
 * "all configurable by Admin". {@link PlatformSettingsService} now fronts
 * each {@code @Value} field below with an optional PLATFORM-scoped
 * {@code settings} override (SRS 15.15) - the {@code @Value} fields stay
 * as-is and remain the effective value until an Admin actually writes an
 * override row, so Phase 1-13 behavior is unchanged by default.
 *
 * WHAT ESCALATION MEANS: per ComplaintStateMachine's own "ESCALATED is an
 * annotation, not a status" decision (Phase 6), this sweep never changes
 * {@code complaints.status} - it only sets {@code isEscalated}/
 * {@code escalatedAt}, matching SRS 14.1's "the underlying status
 * continues to progress normally once escalated" behavior. No
 * {@link com.jannetai.backend.entity.StatusHistory} row is written for
 * an escalation event (a same-status "transition" would misrepresent
 * that table's "every status transition" contract); an
 * {@link com.jannetai.backend.entity.AuditLog} entry is written instead,
 * which is exactly what that table is for (system/administrative events
 * that aren't themselves a status change).
 *
 * SCOPE: only annotates the flag - it does not reassign the complaint to
 * a different department/officer (SRS 15.7's "escalated to the Department
 * Head" is read as "surfaced on their queue via the existing
 * isEscalated-annotated complaint list", not a routing action).
 *
 * PHASE 15 ADDITION (Notification Module, SRS 15.13 Business Rules:
 * "officers receive ... SLA-breach warnings at 80% of SLA time elapsed"):
 * {@link #sweepForSlaWarnings()} runs alongside the existing 100%-breach
 * sweep above, using the SAME per-severity SLA-hours values (via the same
 * {@link #effectiveSlaHours} helper) but an earlier 80% cutoff -
 * {@link com.jannetai.backend.repository.ComplaintRepository#findSlaWarningCandidates}'s
 * Javadoc has the exact window definition. De-duplication (never warn the
 * same complaint twice) is handled here via the existing
 * {@link com.jannetai.backend.entity.AuditLog} trail - the same
 * "AuditLog instead of a fabricated StatusHistory row for a non-status
 * event" precedent this class already established for escalation - rather
 * than adding a new boolean column to the complaints table for a single
 * scheduler's internal bookkeeping.
 */
@Service
@RequiredArgsConstructor
public class EscalationSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(EscalationSchedulerService.class);

    /** Only complaints still actively being worked count as escalatable - see class Javadoc. */
    private static final Set<ComplaintStatus> ESCALATABLE_STATUSES = Set.of(
            ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS);

    private static final String SLA_WARNING_ACTION_TYPE = "COMPLAINT_SLA_WARNING_SENT";

    private final ComplaintRepository complaintRepository;
    private final AuditService auditService;
    private final AuditLogRepository auditLogRepository; // Phase 15: de-dup check for sweepForSlaWarnings
    private final PlatformSettingsService platformSettingsService;
    private final NotificationService notificationService; // Phase 15

    @Value("${app.escalation.sla-hours.critical}")
    private long criticalSlaHours;

    @Value("${app.escalation.sla-hours.high}")
    private long highSlaHours;

    @Value("${app.escalation.sla-hours.medium}")
    private long mediumSlaHours;

    @Value("${app.escalation.sla-hours.low}")
    private long lowSlaHours;

    /**
     * Runs on the fixed delay configured by
     * {@code app.escalation.sweep-interval-ms} (default 15 minutes) -
     * frequent enough that a breach is caught reasonably promptly without
     * hammering the database, given this sandbox has no real production
     * load to tune against (documented placeholder, same honesty
     * convention as every other undocumented-in-SRS numeric default in
     * this project).
     */
    @Scheduled(fixedDelayString = "${app.escalation.sweep-interval-ms}")
    @Transactional
    public void sweepForSlaBreaches() {
        int totalEscalated = 0;
        totalEscalated += escalateBreachesForSeverity(Severity.CRITICAL,
                effectiveSlaHours(PlatformSettingKey.SLA_HOURS_CRITICAL, criticalSlaHours));
        totalEscalated += escalateBreachesForSeverity(Severity.HIGH,
                effectiveSlaHours(PlatformSettingKey.SLA_HOURS_HIGH, highSlaHours));
        totalEscalated += escalateBreachesForSeverity(Severity.MEDIUM,
                effectiveSlaHours(PlatformSettingKey.SLA_HOURS_MEDIUM, mediumSlaHours));
        totalEscalated += escalateBreachesForSeverity(Severity.LOW,
                effectiveSlaHours(PlatformSettingKey.SLA_HOURS_LOW, lowSlaHours));
        if (totalEscalated > 0) {
            log.info("Escalation sweep: {} complaint(s) newly flagged as SLA-breached", totalEscalated);
        }
    }

    /** Phase 14: Admin-set PLATFORM setting override, falling back to the app.escalation.sla-hours.* @Value default. */
    private long effectiveSlaHours(PlatformSettingKey key, long fallbackDefault) {
        return platformSettingsService.getOverride(key).map(Long::parseLong).orElse(fallbackDefault);
    }

    /**
     * Phase 15 (Notification Module, SRS 15.13). Runs on the same fixed
     * delay as {@link #sweepForSlaBreaches()} (a separate {@code @Scheduled}
     * method rather than folded into that one, so a future change to
     * either sweep's cadence doesn't have to touch the other).
     */
    @Scheduled(fixedDelayString = "${app.escalation.sweep-interval-ms}")
    @Transactional
    public void sweepForSlaWarnings() {
        int totalWarned = 0;
        totalWarned += warnForSeverity(Severity.CRITICAL,
                effectiveSlaHours(PlatformSettingKey.SLA_HOURS_CRITICAL, criticalSlaHours));
        totalWarned += warnForSeverity(Severity.HIGH,
                effectiveSlaHours(PlatformSettingKey.SLA_HOURS_HIGH, highSlaHours));
        totalWarned += warnForSeverity(Severity.MEDIUM,
                effectiveSlaHours(PlatformSettingKey.SLA_HOURS_MEDIUM, mediumSlaHours));
        totalWarned += warnForSeverity(Severity.LOW,
                effectiveSlaHours(PlatformSettingKey.SLA_HOURS_LOW, lowSlaHours));
        if (totalWarned > 0) {
            log.info("SLA-warning sweep: {} complaint(s) newly warned at 80% of their SLA window", totalWarned);
        }
    }

    private int warnForSeverity(Severity severity, long slaHours) {
        LocalDateTime breachCutoff = LocalDateTime.now().minusHours(slaHours);
        LocalDateTime warningCutoff = LocalDateTime.now().minusHours(Math.round(slaHours * 0.8));
        List<Complaint> candidates = complaintRepository.findSlaWarningCandidates(
                ESCALATABLE_STATUSES, severity, warningCutoff, breachCutoff);

        int warned = 0;
        for (Complaint complaint : candidates) {
            // De-dup: skip if this complaint was already warned (see class Javadoc).
            if (!auditLogRepository.findByEntityTypeAndEntityIdAndActionTypeOrderByCreatedAtAsc(
                    "COMPLAINT", complaint.getComplaintId(), SLA_WARNING_ACTION_TYPE).isEmpty()) {
                continue;
            }
            notificationService.notifySlaBreachWarning(complaint, complaint.getAssignedOfficer());
            auditService.record(null, SLA_WARNING_ACTION_TYPE, "COMPLAINT", complaint.getComplaintId(),
                    "{\"severity\":\"" + severity + "\",\"sla_hours\":" + slaHours + "}");
            warned++;
        }
        return warned;
    }

    private int escalateBreachesForSeverity(Severity severity, long slaHours) {
        LocalDateTime breachCutoff = LocalDateTime.now().minusHours(slaHours);
        List<Complaint> breached = complaintRepository.findSlaBreachCandidates(
                ESCALATABLE_STATUSES, severity, breachCutoff);

        for (Complaint complaint : breached) {
            complaint.setIsEscalated(true);
            complaint.setEscalatedAt(LocalDateTime.now());
            complaintRepository.save(complaint);

            auditService.record(null, "COMPLAINT_ESCALATED_SLA_BREACH", "COMPLAINT", complaint.getComplaintId(),
                    "{\"severity\":\"" + severity + "\",\"sla_hours\":" + slaHours + "}");

            log.info("Complaint {} escalated: severity={} exceeded its {}-hour SLA (status={})",
                    complaint.getComplaintId(), severity, slaHours, complaint.getStatus());

            // Audit GAP-023 (SRS 15.13 "escalation triggered"): alert the
            // Department Head and the assigned officer.
            notificationService.notifyComplaintEscalated(complaint, slaHours);
        }
        return breached.size();
    }
}
