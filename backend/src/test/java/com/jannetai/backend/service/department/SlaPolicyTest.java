package com.jannetai.backend.service.department;

import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Severity;
import com.jannetai.backend.service.admin.PlatformSettingKey;
import com.jannetai.backend.service.admin.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Audit GAP-027: the SLA clock is fixed on entry to Assigned/In Progress and not retroactive. */
@ExtendWith(MockitoExtension.class)
class SlaPolicyTest {

    @Mock private PlatformSettingsService platformSettingsService;
    private SlaPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new SlaPolicy(platformSettingsService);
        ReflectionTestUtils.setField(policy, "criticalSlaHours", 24L);
        ReflectionTestUtils.setField(policy, "highSlaHours", 72L);
        ReflectionTestUtils.setField(policy, "mediumSlaHours", 168L);
        ReflectionTestUtils.setField(policy, "lowSlaHours", 336L);
        lenient().when(platformSettingsService.getOverride(any())).thenReturn(Optional.empty());
    }

    private Complaint complaint(Severity severity, ComplaintStatus status) {
        return Complaint.builder().complaintId(1L).severity(severity).status(status)
                .createdAt(LocalDateTime.now().minusDays(10)).updatedAt(LocalDateTime.now()).build();
    }

    @Test
    void enteringAssignedStartsTheClockWithTheSlaInForce() {
        Complaint c = complaint(Severity.HIGH, ComplaintStatus.ASSIGNED);
        LocalDateTime before = LocalDateTime.now();

        policy.onStatusChange(c, ComplaintStatus.ASSIGNED);

        assertThat(c.getSlaHours()).isEqualTo(72);
        assertThat(c.getSlaStartedAt()).isAfterOrEqualTo(before);
        assertThat(c.getSlaDueAt()).isEqualTo(c.getSlaStartedAt().plusHours(72));
        assertThat(c.getSlaWarningAt()).isEqualTo(c.getSlaStartedAt().plusMinutes(Math.round(72 * 60 * 0.8)));
        assertThat(policy.dueAt(c)).isEqualTo(c.getSlaDueAt()); // displayed == escalation basis
    }

    @Test
    void otherTransitionsDoNotTouchTheClock() {
        Complaint c = complaint(Severity.HIGH, ComplaintStatus.RESOLVED);
        policy.onStatusChange(c, ComplaintStatus.RESOLVED);
        policy.onStatusChange(c, ComplaintStatus.VERIFIED);
        assertThat(c.getSlaDueAt()).isNull();
    }

    @Test
    void aLaterSettingsChangeIsNotRetroactive() {
        Complaint c = complaint(Severity.HIGH, ComplaintStatus.ASSIGNED);
        policy.onStatusChange(c, ComplaintStatus.ASSIGNED);
        LocalDateTime due = c.getSlaDueAt();

        // dueAt() returns the already-persisted sla_due_at field and never
        // re-reads the platform setting, so this stub is never consulted -
        // that IS the point of the test (a later settings change must not be
        // retroactive). Left lenient rather than removed, as documentation of
        // "even if someone changed it to 24h just now, nothing here uses it".
        lenient().when(platformSettingsService.getOverride(PlatformSettingKey.SLA_HOURS_HIGH)).thenReturn(Optional.of("24"));

        assertThat(policy.dueAt(c)).isEqualTo(due);   // stored window unchanged
        assertThat(c.getSlaHours()).isEqualTo(72);
    }

    @Test
    void aSeverityCorrectionKeepsTheStartAndAppliesTheNewSeverity() {
        Complaint c = complaint(Severity.MEDIUM, ComplaintStatus.IN_PROGRESS);
        policy.startClock(c, LocalDateTime.of(2026, 9, 1, 10, 0));
        c.setSeverity(Severity.CRITICAL);

        policy.onSeverityChange(c);

        assertThat(c.getSlaStartedAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 10, 0));
        assertThat(c.getSlaDueAt()).isEqualTo(LocalDateTime.of(2026, 9, 2, 10, 0));
    }

    @Test
    void noSeverityMeansNoClockAndTheOldEstimateIsShown() {
        Complaint c = complaint(null, ComplaintStatus.ASSIGNED);
        policy.onStatusChange(c, ComplaintStatus.ASSIGNED);
        assertThat(c.getSlaDueAt()).isNull();
        assertThat(policy.dueAt(c)).isNull();
    }
}
