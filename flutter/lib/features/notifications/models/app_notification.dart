/// Mirrors NotificationResponse (backend, dto/notification/) - one row of
/// the notification list (SRS 20.5 `GET /api/v1/notifications`, Phase 15).
class AppNotification {
  final int notificationId;
  final String? complaintReferenceNumber;
  final String channel;
  final String message;
  final String deliveryStatus;
  final DateTime? createdAt;

  AppNotification({
    required this.notificationId,
    this.complaintReferenceNumber,
    required this.channel,
    required this.message,
    required this.deliveryStatus,
    this.createdAt,
  });

  factory AppNotification.fromJson(Map<String, dynamic> json) {
    return AppNotification(
      notificationId: json['notificationId'] as int,
      complaintReferenceNumber: json['complaintReferenceNumber'] as String?,
      channel: json['channel'] as String,
      message: json['message'] as String,
      deliveryStatus: json['deliveryStatus'] as String,
      createdAt: json['createdAt'] != null ? DateTime.tryParse(json['createdAt'] as String) : null,
    );
  }
}
