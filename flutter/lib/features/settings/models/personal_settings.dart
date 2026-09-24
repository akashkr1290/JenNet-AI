import '../../notifications/models/notification_preferences.dart';

/// Mirrors PersonalSettingsResponse (backend, dto/settings/) - SRS 15.15
/// `GET/PATCH /api/v1/users/me/settings` (Phase 15, Personal Settings).
/// [officerAvailabilityStatus] is null for any non-GOVERNMENT_OFFICER
/// account (server-side rule - see PersonalSettingKey's Javadoc).
class PersonalSettings {
  final String language;
  final bool highContrastEnabled;
  final String? officerAvailabilityStatus;
  final NotificationPreferences notificationPreferences;

  PersonalSettings({
    required this.language,
    required this.highContrastEnabled,
    this.officerAvailabilityStatus,
    required this.notificationPreferences,
  });

  factory PersonalSettings.fromJson(Map<String, dynamic> json) {
    return PersonalSettings(
      language: json['language'] as String,
      highContrastEnabled: json['highContrastEnabled'] as bool,
      officerAvailabilityStatus: json['officerAvailabilityStatus'] as String?,
      notificationPreferences:
          NotificationPreferences.fromJson(json['notificationPreferences'] as Map<String, dynamic>),
    );
  }
}
