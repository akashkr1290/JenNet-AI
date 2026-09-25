package com.jannetai.backend.service.report;

import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.service.notification.EmailGatewayClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * Gap-backlog Patch 18 (Sep 2026 strict recheck): scheduled weekly report -
 * every active DEPARTMENT_HEAD with an email on file is sent their own
 * department's report for the prior 7 days (Monday 08:00 IST by default; audit
 * GAP-039 / US-10 - previously the 90-day dashboard overview). Uses the same
 * EmailGatewayClient as notifications, so with email disabled it logs an
 * [EMAIL-STUB] line instead of sending. One failing recipient never stops
 * the rest.
 */
@Component
@RequiredArgsConstructor
public class WeeklyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportScheduler.class);

    private final UserRepository userRepository;
    private final ReportPdfService reportPdfService;
    private final PeriodReportService periodReportService; // audit GAP-039
    private final EmailGatewayClient emailGatewayClient;

    @Value("${app.reports.weekly-enabled:true}")
    private boolean weeklyEnabled;

    @Scheduled(cron = "${app.reports.weekly-cron:0 0 8 * * MON}", zone = "${app.reports.zone:Asia/Kolkata}")
    // No transaction here (audit GAP-059 lesson): each department's report is generated in its own
    // transaction by PeriodReportService, so one failure cannot roll back the others.
    public void sendWeeklyDepartmentReports() {
        if (!weeklyEnabled) {
            return;
        }
        int sent = 0;
        int failed = 0;
        for (User head : userRepository.findByRoleAndStatus(Role.DEPARTMENT_HEAD, UserStatus.ACTIVE)) {
            if (head.getEmail() == null || head.getEmail().isBlank() || head.getDepartment() == null) {
                continue;
            }
            try {
                // Audit GAP-039 / US-10: the prior 7 days (the week ending yesterday, in app.reports.zone),
                // not the 90-day dashboard overview; stored as a snapshot like any other period report.
                com.jannetai.backend.dto.report.PeriodReport report = periodReportService.generateScheduled(
                        ReportPeriods.Type.WEEKLY, null, null, head.getDepartment().getDepartmentId());
                byte[] pdf = reportPdfService.periodPdf(report);
                emailGatewayClient.sendWithAttachment(head.getEmail(),
                        "JanNet AI - Weekly Department Report (" + report.periodStart() + " to " + report.periodEnd() + ")",
                        report.insufficientData()
                                ? "Attached is your department's report for the prior 7 days. Too few complaints were "
                                        + "received in this period for representative figures (labelled INSUFFICIENT DATA)."
                                : "Attached is your department's report for the prior 7 days.",
                        "jannet-weekly-report-" + report.periodStart() + "_" + report.periodEnd() + ".pdf", pdf,
                        "application/pdf");
                sent++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("Weekly report for user {} failed: {}", head.getUserId(), e.getMessage());
            }
        }
        log.info("Weekly department reports: {} sent, {} failed", sent, failed);
    }
}
