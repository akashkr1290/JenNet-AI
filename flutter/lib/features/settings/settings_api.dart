import '../../core/api/api_client.dart';
import 'models/personal_settings.dart';

/// Phase 15 (Personal Settings, SRS 15.15). `/users/me/settings` -
/// deliberately a sibling of UserApi.me() rather than folded into it
/// (see UserController's Javadoc: these are `settings` table rows, not
/// `users` table columns).
class SettingsApi {
  SettingsApi._();
  static final SettingsApi instance = SettingsApi._();

  final _client = ApiClient.instance;

  Future<PersonalSettings> getSettings() async {
    final json = await _client.get('/users/me/settings') as Map<String, dynamic>;
    return PersonalSettings.fromJson(json);
  }

  /// Partial update - a null field leaves that setting unchanged
  /// server-side (see PersonalSettingsUpdateRequest's Javadoc).
  /// [officerAvailabilityStatus] is rejected with 403 server-side for any
  /// non-GOVERNMENT_OFFICER caller.
  Future<PersonalSettings> updateSettings({
    String? language,
    bool? highContrastEnabled,
    String? officerAvailabilityStatus,
  }) async {
    final json = await _client.patch('/users/me/settings', body: {
      if (language != null) 'language': language,
      if (highContrastEnabled != null) 'highContrastEnabled': highContrastEnabled,
      if (officerAvailabilityStatus != null) 'officerAvailabilityStatus': officerAvailabilityStatus,
    }) as Map<String, dynamic>;
    return PersonalSettings.fromJson(json);
  }
}
