package com.jannetai.backend.service.notification;

/**
 * Thrown by {@link EmailGatewayClient}/{@link SmsGatewayClient} on any
 * failed send attempt (disabled channel, network failure, non-2xx
 * response, mail transport error). Unchecked, mirroring
 * {@code AiServiceCallException}'s style (Phase 8) - callers
 * ({@link NotificationService}) decide whether to retry, not the
 * gateway itself.
 */
public class NotificationDeliveryException extends RuntimeException {

    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
