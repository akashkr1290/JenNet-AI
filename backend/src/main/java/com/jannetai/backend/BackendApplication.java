package com.jannetai.backend;

import com.jannetai.backend.config.AiServiceProperties;
import com.jannetai.backend.config.JwtProperties;
import com.jannetai.backend.config.NotificationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * JANNet AI - Spring Boot business backend entry point.
 *
 * Owns authentication, authorization, the complaint workflow/state machine,
 * department assignment, notifications, and all persistence (ARCHITECTURE.md
 * Section 2.2). It is the only service the Flutter client talks to directly.
 *
 * Phase 3 built the skeleton (entities/repositories/Flyway/error shape, no
 * security). Phase 4 adds Spring Security, JWT issuance/validation, RBAC,
 * and the Authentication Module (SRS 15.2) on top of it. Phase 8 adds the
 * ai-service integration on top of Phase 6's Complaint Module. Phase 11
 * adds @EnableScheduling for EscalationSchedulerService's SLA-breach sweep
 * (SRS 15.7) - the first in-process scheduled job this backend runs.
 * Phase 15 registers {@link NotificationProperties} for the Notification
 * Module's real email/SMS gateway configuration (SRS 15.13).
 */
@SpringBootApplication
@EnableConfigurationProperties({JwtProperties.class, AiServiceProperties.class, NotificationProperties.class})
@EnableScheduling
public class BackendApplication {

    /**
     * Audit GAP-026: all LocalDateTime values (entities, LocalDateTime.now(),
     * Connector/J conversions with serverTimezone=UTC) are UTC wall-clock times,
     * independent of the host's zone. Containers already run in UTC; this makes a
     * developer machine in IST behave the same. Set in a static initialiser so it
     * also applies to @SpringBootTest, which never calls main().
     * Business-day logic that needs Indian time uses an explicit zone
     * (e.g. app.reports.zone: Asia/Kolkata).
     */
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
