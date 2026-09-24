package com.jannetai.backend.dto.notification;

import com.jannetai.backend.entity.Notification;
import com.jannetai.backend.entity.enums.DeliveryStatus;
import com.jannetai.backend.entity.enums.NotificationChannel;

import java.time.LocalDateTime;

/** One row of the citizen/officer notification list (SRS 20.5 GET /api/v1/notifications). */
public record NotificationResponse(
        Long notificationId,
        String complaintReferenceNumber,
        NotificationChannel channel,
        String message,
        DeliveryStatus deliveryStatus,
        LocalDateTime createdAt
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getNotificationId(),
                notification.getComplaint() != null ? notification.getComplaint().getReferenceNumber() : null,
                notification.getChannel(),
                notification.getMessage(),
                notification.getDeliveryStatus(),
                notification.getCreatedAt());
    }
}
