import '../../core/api/api_client.dart';
import 'models/app_notification.dart';
import 'models/notification_preferences.dart';

/// Phase 15 (Notification Module, SRS 15.13 / 20.5). Every method here
/// scopes to the signed-in user's own notifications/preferences - the
/// backend has no "view another user's notifications" endpoint to call.
class NotificationApi {
  NotificationApi._();
  static final NotificationApi instance = NotificationApi._();

  final _client = ApiClient.instance;

  Future<List<AppNotification>> list({int page = 0, int pageSize = 20}) async {
    final json = await _client.get('/notifications', query: {
      'page': page,
      'pageSize': pageSize,
    }) as Map<String, dynamic>;
    final content = (json['content'] as List?) ?? [];
    return content.map((e) => AppNotification.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<NotificationPreferences> getPreferences() async {
    final json = await _client.get('/notifications/preferences') as Map<String, dynamic>;
    return NotificationPreferences.fromJson(json);
  }

  /// Partial update - a null field leaves that preference unchanged
  /// server-side (see NotificationPreferencesUpdateRequest's Javadoc).
  Future<NotificationPreferences> updatePreferences({bool? smsEnabled, bool? pushEnabled, bool? emailEnabled}) async {
    final json = await _client.put('/notifications/preferences', body: {
      if (smsEnabled != null) 'smsEnabled': smsEnabled,
      if (pushEnabled != null) 'pushEnabled': pushEnabled,
      if (emailEnabled != null) 'emailEnabled': emailEnabled,
    }) as Map<String, dynamic>;
    return NotificationPreferences.fromJson(json);
  }
}
