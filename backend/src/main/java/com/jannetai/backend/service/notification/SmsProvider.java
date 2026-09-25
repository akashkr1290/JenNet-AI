package com.jannetai.backend.service.notification;

import com.jannetai.backend.config.NotificationProperties;

/**
 * Audit GAP-003: strategy interface for SMS transmission. {@link SmsGatewayClient}
 * keeps the provider-independent parts (enable switch, number validation and
 * formatting, PII masking, SKIPPED outcome) and hands the actual transmission
 * to the adapter whose {@link #id()} equals {@code app.notification.sms.provider}
 * (SMS_PROVIDER).
 *
 * The built-in {@link GenericHttpSmsProvider} ("generic-http") covers
 * HTTP/JSON or form APIs purely through configuration. A provider whose API
 * cannot be expressed that way (SOAP, SMPP, request signing) gets its own
 * implementation of this interface as a Spring bean with a new id - no other
 * class changes.
 */
public interface SmsProvider {

    /** Matched against {@code app.notification.sms.provider}. */
    String id();

    /**
     * Names (never values) of settings this adapter needs that are missing;
     * empty when it is ready to send.
     */
    String missingConfiguration(NotificationProperties.Sms settings);

    /**
     * Transmits one message.
     *
     * @param e164Number recipient as {@code +<country code><number>}
     * @throws NotificationDeliveryException when the provider did not accept the message
     */
    void send(NotificationProperties.Sms settings, String e164Number, String message, SmsMessageType type);
}
