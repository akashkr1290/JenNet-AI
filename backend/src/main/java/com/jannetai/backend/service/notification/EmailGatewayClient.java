package com.jannetai.backend.service.notification;

import com.jannetai.backend.config.NotificationProperties;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Real SMTP email delivery for the Notification Module (Phase 15, SRS
 * 15.13). Gated by {@code app.notification.email.enabled}
 * ({@link NotificationProperties.Email#isEnabled()}) - when {@code false}
 * (the default for this workspace, which has no real SMTP credentials
 * configured), {@link #send} logs what it would have sent instead of
 * attempting a real connection (and reports {@link DeliveryOutcome#SKIPPED},
 * audit GAP-022), same honest-stub convention as the Phase
 * 4 {@code LoggingOtpDeliveryService} this phase replaces, rather than
 * letting every citizen-facing status change fail outright with a mail
 * transport error in an unconfigured environment.
 *
 * Uses Spring Boot's auto-configured {@link JavaMailSender} bean, itself
 * driven by {@code spring.mail.*} properties (host/port/username/
 * password - see application.yml) - no custom transport code needed.
 */
@Component
@RequiredArgsConstructor
public class EmailGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(EmailGatewayClient.class);

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;

    /** Audit GAP-004: true when email can actually be sent (NOTIFICATION_EMAIL_ENABLED=true). */
    public boolean isConfigured() {
        return properties.getEmail().isEnabled();
    }

    /**
     * @return {@link DeliveryOutcome#SENT} once the SMTP server accepted the
     *         message; {@link DeliveryOutcome#SKIPPED} when email is disabled or
     *         the recipient has no address (audit GAP-022 - previously such
     *         messages were recorded as DELIVERED)
     * @throws NotificationDeliveryException on any transport failure -
     *         never thrown merely because the channel is disabled.
     */
    public DeliveryOutcome send(String toAddress, String subject, String body) {
        if (!properties.getEmail().isEnabled() || toAddress == null || toAddress.isBlank()) {
            // Remaining-gaps item 15: recipient masked, body not logged (length only).
            log.warn("[EMAIL-STUB] Would send email to {} (subject=\"{}\", {} chars) - "
                            + "app.notification.email.enabled is false or recipient has no email on file.",
                    PiiMask.email(toAddress), subject, body == null ? 0 : body.length());
            return DeliveryOutcome.SKIPPED;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(properties.getEmail().getFromAddress());
            helper.setTo(toAddress);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
        } catch (MailException | jakarta.mail.MessagingException e) {
            throw new NotificationDeliveryException("Failed to send email to " + PiiMask.email(toAddress) + ": " + e.getMessage(), e);
        }
        return DeliveryOutcome.SENT;
    }

    /** Gap-backlog Patch 18: same enable/stub behaviour as send(), with one attachment. */
    public DeliveryOutcome sendWithAttachment(String toAddress, String subject, String body,
                                              String fileName, byte[] content, String contentType) {
        if (!properties.getEmail().isEnabled() || toAddress == null || toAddress.isBlank()) {
            log.warn("[EMAIL-STUB] Would send email with attachment {} ({} bytes) to {} (subject=\"{}\")",
                    fileName, content.length, PiiMask.email(toAddress), subject);
            return DeliveryOutcome.SKIPPED;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(properties.getEmail().getFromAddress());
            helper.setTo(toAddress);
            helper.setSubject(subject);
            helper.setText(body, false);
            helper.addAttachment(fileName, new org.springframework.core.io.ByteArrayResource(content), contentType);
            mailSender.send(message);
        } catch (MailException | jakarta.mail.MessagingException e) {
            throw new NotificationDeliveryException("Failed to send email to " + PiiMask.email(toAddress) + ": " + e.getMessage(), e);
        }
        return DeliveryOutcome.SENT;
    }
}
