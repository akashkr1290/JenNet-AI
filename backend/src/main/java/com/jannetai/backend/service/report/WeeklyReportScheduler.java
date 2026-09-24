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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Gap-backlog Patch 18 (Sep 2026 strict recheck): scheduled weekly report -
 * every active DEPARTMENT_HEAD with an email on file is sent their own
 * department's overview PDF (Monday 08:00 IST by default). Uses the same
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
    private final EmailGatewayClient emailGatewayClient;

    @Value("${app.reports.weekly-enabled:true}")
    private boolean weeklyEnabled;

    @Scheduled(cron = "${app.reports.weekly-cron:0 0 8 * * MON}", zone = "${app.reports.zone:Asia/Kolkata}")
    @Transactional(readOnly = true)
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
                byte[] pdf = reportPdfService.overviewPdf(head, null);
                emailGatewayClient.sendWithAttachment(head.getEmail(),
                        "JanNet AI - Weekly Department Report (" + LocalDate.now() + ")",
                        "Attached is this week's complaint overview for your department.",
                        "jannet-weekly-report-" + LocalDate.now() + ".pdf", pdf, "application/pdf");
                sent++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("Weekly report for user {} failed: {}", head.getUserId(), e.getMessage());
            }
        }
        log.info("Weekly department reports: {} sent, {} failed", sent, failed);
    }
}
