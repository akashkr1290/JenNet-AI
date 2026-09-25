package com.jannetai.backend.service.department;

import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Severity;
import com.jannetai.backend.service.admin.PlatformSettingKey;
import com.jannetai.backend.service.admin.PlatformSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Gap-backlog Patch 08/09 (Sep 2026 strict recheck): the per-severity SLA
 * hours the escalation sweep uses, exposed read-only so the citizen and
 * officer dashboards show SLA due-times computed from the same config keys
 * and the same admin platform-setting overrides as
 * {@link EscalationSchedulerService} - not a second, divergent SLA table.
 * A complaint with no severity yet (not through prediction) has no SLA due
 * time; that is reported as null, never guessed.
 */
@Component
@RequiredArgsConstructor
public class SlaPolicy {

    private final PlatformSettingsService platformSettingsService;

    @Value("${app.escalation.sla-hours.critical}")
    private long criticalSlaHours;

    @Value("${app.escalation.sla-hours.high}")
    private long highSlaHours;

    @Value("${app.escalation.sla-hours.medium}")
    private long mediumSlaHours;

    @Value("${app.escalation.sla-hours.low}")
    private long lowSlaHours;

    public Long slaHoursFor(Severity severity) {
        if (severity == null) {
            return null;
        }
        return switch (severity) {
            case CRITICAL -> effective(PlatformSettingKey.SLA_HOURS_CRITICAL, criticalSlaHours);
            case HIGH -> effective(PlatformSettingKey.SLA_HOURS_HIGH, highSlaHours);
            case MEDIUM -> effective(PlatformSettingKey.SLA_HOURS_MEDIUM, mediumSlaHours);
            case LOW -> effective(PlatformSettingKey.SLA_HOURS_LOW, lowSlaHours);
        };
    }

    /** createdAt + severity SLA hours, or null when either is unknown. */
    /**
     * Audit GAP-027: the due time shown everywhere is the persisted
     * {@code sla_due_at} - the same value escalation uses. Complaints whose
     * clock has not started (not yet ASSIGNED, or open since before V27 and not
     * yet swept) fall back to the previous created_at-based estimate.
     */
    public LocalDateTime dueAt(Complaint complaint) {
        if (complaint.getSlaDueAt() != null) {
            return complaint.getSlaDueAt();
        }
        Long hours = slaHoursFor(complaint.getSeverity());
        if (hours == null || complaint.getCreatedAt() == null) {
            return null;
        }
        return complaint.getCreatedAt().plusHours(hours);
    }

    /** Statuses in which the SLA clock runs (SRS 14.3: Assigned / In Progress). */
    public static final java.util.Set<ComplaintStatus> CLOCKED_STATUSES =
            java.util.EnumSet.of(ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS);

    /**
     * Audit GAP-027: (re)starts the SLA clock when a complaint ENTERS Assigned
     * or In Progress - SRS 14.3 "N hours in Assigned/In Progress without status
     * change". Uses the SLA hours in force NOW and stores them, so later
     * setting changes are not retroactive (SRS 15.11). Other transitions and
     * non-status edits leave the clock untouched. Call before the complaint is
     * saved/flushed.
     */
    public void onStatusChange(Complaint complaint, ComplaintStatus newStatus) {
        if (newStatus != null && CLOCKED_STATUSES.contains(newStatus)) {
            startClock(complaint, LocalDateTime.now());
        }
    }

    /**
     * Audit GAP-027: a severity correction re-times the running window from its
     * original start with the new severity's SLA (a different SLA class, not a
     * retroactive settings change). No effect when no clock is running.
     */
    public void onSeverityChange(Complaint complaint) {
        if (complaint.getSlaStartedAt() != null && CLOCKED_STATUSES.contains(complaint.getStatus())) {
            startClock(complaint, complaint.getSlaStartedAt());
        }
    }

    /** Starts the clock at {@code startedAt}; clears it when the severity has no SLA yet. */
    public void startClock(Complaint complaint, LocalDateTime startedAt) {
        Long hours = slaHoursFor(complaint.getSeverity());
        if (hours == null || hours < 1) {
            complaint.setSlaStartedAt(null);
            complaint.setSlaHours(null);
            complaint.setSlaWarningAt(null);
            complaint.setSlaDueAt(null);
            return;
        }
        SlaWindow window = SlaWindow.starting(startedAt, hours);
        complaint.setSlaStartedAt(window.startedAt());
        complaint.setSlaHours(window.hours());
        complaint.setSlaWarningAt(window.warningAt());
        complaint.setSlaDueAt(window.dueAt());
    }

    private long effective(PlatformSettingKey key, long fallbackDefault) {
        return platformSettingsService.getOverride(key).map(Long::parseLong).orElse(fallbackDefault);
    }
}
