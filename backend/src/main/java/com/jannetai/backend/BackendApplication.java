package com.jannetai.backend;

import com.jannetai.backend.config.AiServiceProperties;
import com.jannetai.backend.config.JwtProperties;
import com.jannetai.backend.config.NotificationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

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

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
