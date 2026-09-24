package com.jannetai.backend.entity.enums;

/** Matches notifications.channel CHECK constraint (V11__create_notifications.sql). */
public enum NotificationChannel {
    IN_APP,
    SMS,
    EMAIL,
    PUSH
}
