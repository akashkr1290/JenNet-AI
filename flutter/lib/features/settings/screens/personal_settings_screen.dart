import '../../../core/app_preferences.dart';
import '../../../core/l10n/app_strings.dart';
import 'privacy_policy_screen.dart';
import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../../auth/auth_api.dart';
import '../../auth/screens/login_screen.dart';
import '../../notifications/notification_api.dart';
import '../models/personal_settings.dart';
import '../settings_api.dart';

/// SRS 15.15 (Personal Settings, Phase 15) - language, accessibility, and
/// (officer-only) availability status, plus the notification channel
/// toggles (SRS 15.13 / 20.5, owned server-side by NotificationService
/// but surfaced here alongside the rest of Settings for one screen the
/// Flutter side can show every role). Each control simply doesn't render
/// when not applicable (officer availability for a non-officer), rather
/// than branching into separate per-role screens the way
/// AdminHomeScreen/DepartmentHeadHomeScreen do for whole tab sets - these
/// controls are each independent single rows, not a materially different
/// screen shape per role.
class PersonalSettingsScreen extends StatefulWidget {
  const PersonalSettingsScreen({super.key});

  @override
  State<PersonalSettingsScreen> createState() => _PersonalSettingsScreenState();
}

class _PersonalSettingsScreenState extends State<PersonalSettingsScreen> {
  late Future<PersonalSettings> _future;
  bool _saving = false;

  static const _languageLabels = {'EN': 'English', 'HI': 'Hindi'};
  static const _officerStatusLabels = {
    'AVAILABLE': 'Available',
    'BUSY': 'Busy',
    'ON_LEAVE': 'On Leave',
  };

  @override
  void initState() {
    super.initState();
    _future = SettingsApi.instance.getSettings();
  }

  void _refresh() {
    setState(() => _future = SettingsApi.instance.getSettings());
  }

  void _showError(Object e) {
    final message = e is ApiException ? e.message : 'Something went wrong.';
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  /// Runs [action], then re-fetches the bundled settings so every control
  /// (including the notification-preference switches, which are updated
  /// via a separate endpoint - see [_updateNotificationPreference]) shows
  /// the freshly-saved server state rather than an optimistic local guess.
  Future<void> _save(Future<void> Function() action) async {
    setState(() => _saving = true);
    try {
      await action();
      _refresh();
    } catch (e) {
      _showError(e);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _updateLanguage(String value) {
    AppStrings.currentLanguage.value = value;
    return _save(() => SettingsApi.instance.updateSettings(language: value));
  }

  Future<void> _updateHighContrast(bool value) {
    AppPreferences.highContrast.value = value;
    return _save(() => SettingsApi.instance.updateSettings(highContrastEnabled: value));
  }

  Future<void> _updateOfficerStatus(String value) =>
      _save(() => SettingsApi.instance.updateSettings(officerAvailabilityStatus: value));

  Future<void> _updateNotificationPreference({bool? smsEnabled, bool? pushEnabled, bool? emailEnabled}) => _save(
      () => NotificationApi.instance.updatePreferences(
          smsEnabled: smsEnabled, pushEnabled: pushEnabled, emailEnabled: emailEnabled));

  /// Phase 17 addition: SRS 27.5 "logout everywhere" self-service session
  /// revocation (POST /auth/logout-all - AuthController, unmodified since
  /// it was added). No prior phase surfaced this anywhere in Flutter;
  /// Settings is this project's established home for account-level
  /// actions (see the class doc comment), so it's added here rather than
  /// on a role-specific home shell.
  Future<void> _logoutFromAllDevices() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Log out of all devices?'),
        content: const Text('This will end every active session, including this one. You will need to sign in again.'),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Log Out All')),
        ],
      ),
    );
    if (confirmed != true) return;
    await AuthApi.instance.logoutAll();
    if (!mounted) return;
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const LoginScreen()),
      (route) => false,
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Settings'), actions: [
        IconButton(
          tooltip: 'Privacy & Data Use',
          icon: const Icon(Icons.privacy_tip_outlined),
          onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const PrivacyPolicyScreen())),
        ),
        IconButton(
          tooltip: 'Terms of Use',
          icon: const Icon(Icons.description_outlined),
          onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const TermsOfUseScreen())),
        ),
      ]),
      body: FutureBuilder<PersonalSettings>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError || !snapshot.hasData) {
            final message = snapshot.error is ApiException
                ? (snapshot.error as ApiException).message
                : 'Could not load settings.';
            return Center(child: Text(message, textAlign: TextAlign.center));
          }
          final settings = snapshot.data!;
          return AbsorbPointer(
            absorbing: _saving,
            child: Opacity(
              opacity: _saving ? 0.6 : 1,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  _sectionHeader(context, 'Language & Accessibility'),
                  Card(
                    child: Column(
                      children: [
                        ListTile(
                          title: const Text('Language'),
                          trailing: DropdownButton<String>(
                            value: settings.language,
                            items: _languageLabels.entries
                                .map((e) => DropdownMenuItem(value: e.key, child: Text(e.value)))
                                .toList(),
                            onChanged: (value) {
                              if (value != null && value != settings.language) _updateLanguage(value);
                            },
                          ),
                        ),
                        SwitchListTile(
                          title: const Text('High contrast'),
                          value: settings.highContrastEnabled,
                          onChanged: _updateHighContrast,
                        ),
                      ],
                    ),
                  ),
                  if (settings.officerAvailabilityStatus != null) ...[
                    _sectionHeader(context, 'Availability'),
                    Card(
                      child: ListTile(
                        title: const Text('Availability status'),
                        trailing: DropdownButton<String>(
                          value: settings.officerAvailabilityStatus,
                          items: _officerStatusLabels.entries
                              .map((e) => DropdownMenuItem(value: e.key, child: Text(e.value)))
                              .toList(),
                          onChanged: (value) {
                            if (value != null && value != settings.officerAvailabilityStatus) {
                              _updateOfficerStatus(value);
                            }
                          },
                        ),
                      ),
                    ),
                  ],
                  _sectionHeader(context, 'Notifications'),
                  Card(
                    child: Column(
                      children: [
                        SwitchListTile(
                          title: const Text('SMS notifications'),
                          value: settings.notificationPreferences.smsEnabled,
                          onChanged: (value) => _updateNotificationPreference(smsEnabled: value),
                        ),
                        SwitchListTile(
                          title: const Text('Push notifications'),
                          subtitle: const Text('Not yet available - saved for a future update.'),
                          value: settings.notificationPreferences.pushEnabled,
                          onChanged: (value) => _updateNotificationPreference(pushEnabled: value),
                        ),
                        SwitchListTile(
                          title: const Text('Email notifications'),
                          subtitle: const Text('Status updates are always emailed, regardless of this setting.'),
                          value: settings.notificationPreferences.emailEnabled,
                          onChanged: (value) => _updateNotificationPreference(emailEnabled: value),
                        ),
                      ],
                    ),
                  ),
                  _sectionHeader(context, 'Account'),
                  Card(
                    child: ListTile(
                      leading: const Icon(Icons.logout, color: Colors.red),
                      title: const Text('Log out of all devices'),
                      subtitle: const Text('Ends every active session, including this one.'),
                      onTap: _logoutFromAllDevices,
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _sectionHeader(BuildContext context, String text) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 16, 4, 8),
      child: Text(text, style: Theme.of(context).textTheme.titleSmall?.copyWith(color: Colors.grey.shade700)),
    );
  }
}
