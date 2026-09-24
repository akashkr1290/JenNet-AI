package com.jannetai.backend.service.department;

import com.jannetai.backend.entity.Complaint;
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
    public LocalDateTime dueAt(Complaint complaint) {
        Long hours = slaHoursFor(complaint.getSeverity());
        if (hours == null || complaint.getCreatedAt() == null) {
            return null;
        }
        return complaint.getCreatedAt().plusHours(hours);
    }

    private long effective(PlatformSettingKey key, long fallbackDefault) {
        return platformSettingsService.getOverride(key).map(Long::parseLong).orElse(fallbackDefault);
    }
}
