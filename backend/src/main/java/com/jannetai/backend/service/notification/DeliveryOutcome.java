package com.jannetai.backend.service.notification;

/**
 * Audit GAP-022: what a gateway actually did with one message. A failed
 * transmission is signalled by {@link NotificationDeliveryException}, not by
 * a value here, so the retry loop in NotificationService stays unchanged.
 */
public enum DeliveryOutcome {
    /** Handed to the external provider (SMTP server, SMS provider, FCM). */
    SENT,
    /**
     * Nothing was transmitted: the channel is not configured/enabled, or the
     * recipient has no address/device for it. Recorded as SKIPPED, never as DELIVERED.
     */
    SKIPPED
}
