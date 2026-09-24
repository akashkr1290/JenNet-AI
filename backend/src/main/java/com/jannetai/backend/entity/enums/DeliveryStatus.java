package com.jannetai.backend.entity.enums;

/** Matches notifications.delivery_status CHECK constraint (V11__create_notifications.sql). */
public enum DeliveryStatus {
    PENDING,
    DELIVERED,
    FAILED
}
