package com.jannetai.backend.service.notification;

/**
 * Audit GAP-003: in India each SMS must reference the DLT template it was
 * registered under, and OTP and service-notification texts are registered as
 * different templates - so the adapter needs to know which kind it is sending.
 */
public enum SmsMessageType {
    OTP,
    NOTIFICATION
}
