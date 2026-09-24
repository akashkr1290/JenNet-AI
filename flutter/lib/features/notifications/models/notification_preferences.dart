/// Mirrors NotificationPreferencesResponse (backend, dto/notification/) -
/// SRS 20.5 `GET/PUT /api/v1/notifications/preferences` (Phase 15). Note:
/// per that DTO's Javadoc, [emailEnabled] is stored/returned but NOT
/// actually consulted before sending a major status-change/officer-
/// assignment email - those are mandatory regardless (SRS 15.13
/// Exceptions) - the Settings screen surfaces this via helper text rather
/// than letting the toggle silently do nothing.
class NotificationPreferences {
  final bool smsEnabled;
  final bool pushEnabled;
  final bool emailEnabled;

  NotificationPreferences({required this.smsEnabled, required this.pushEnabled, required this.emailEnabled});

  factory NotificationPreferences.fromJson(Map<String, dynamic> json) {
    return NotificationPreferences(
      smsEnabled: json['smsEnabled'] as bool,
      pushEnabled: json['pushEnabled'] as bool,
      emailEnabled: json['emailEnabled'] as bool,
    );
  }
}
