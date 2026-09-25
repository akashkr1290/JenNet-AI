package com.jannetai.backend.entity.enums;

/**
 * Matches notifications.delivery_status CHECK constraint (V11__create_notifications.sql,
 * widened by V24__add_notification_delivery_skipped.sql).
 */
public enum DeliveryStatus {
    PENDING,
    DELIVERED,
    FAILED,
    /** Audit GAP-022: nothing was transmitted (channel not configured, or no address/device). */
    SKIPPED
}
