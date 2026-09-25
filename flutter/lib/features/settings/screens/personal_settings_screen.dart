import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/app_preferences.dart';
import '../../../core/l10n/app_strings.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../../auth/auth_api.dart';
import '../../auth/screens/login_screen.dart';
import '../../notifications/notification_api.dart';
import '../models/personal_settings.dart';
import '../settings_api.dart';
import '../../users/screens/profile_screen.dart';
import '../../users/user_api.dart';
import 'privacy_policy_screen.dart';

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

  /// Profile header only (name + role) - a failure simply hides it.
  UserProfile? _profile;

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
    _loadProfile();
  }

  Future<void> _loadProfile() async {
    try {
      final me = await UserApi.instance.me();
      if (mounted) setState(() => _profile = me);
    } catch (_) {}
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

  static String _roleLabel(String role) {
    switch (role) {
      case 'CITIZEN':
        return 'Citizen';
      case 'OFFICER':
        return 'Field Officer';
      case 'VERIFICATION_TEAM':
        return 'Verification Team';
      case 'DEPARTMENT_HEAD':
        return 'Department Head';
      case 'ADMIN':
        return 'Administrator';
      default:
        return role.replaceAll('_', ' ');
    }
  }

  void _openPrivacy() =>
      Navigator.of(context).push(MaterialPageRoute(builder: (_) => const PrivacyPolicyScreen()));

  // Post-UI gap fix: SRS 15.1 profile management lives on its own screen.
  Future<void> _openProfile() async {
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => const ProfileScreen()));
    if (mounted) _loadProfile();
  }

  void _openTerms() => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const TermsOfUseScreen()));

  @override
  Widget build(BuildContext context) {
    return JanPage(
      title: 'Settings',
      maxWidth: 760,
      actions: [
        IconButton(
          tooltip: 'Privacy & Data Use',
          icon: const Icon(Icons.privacy_tip_outlined),
          onPressed: _openPrivacy,
        ),
        IconButton(
          tooltip: 'Terms of Use',
          icon: const Icon(Icons.description_outlined),
          onPressed: _openTerms,
        ),
      ],
      body: FutureBuilder<PersonalSettings>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const JanLoadingView(message: 'Loading settings...');
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return JanErrorState.fromError(
              snapshot.error,
              fallback: 'Could not load settings.',
              onRetry: _refresh,
            );
          }
          final settings = snapshot.data!;
          return Stack(
            children: [
              AbsorbPointer(
                absorbing: _saving,
                child: Opacity(
                  opacity: _saving ? 0.6 : 1,
                  child: ListView(
                    padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
                    children: [
                      if (_profile != null) _profileHeader(_profile!),
                      const JanSectionHeader(title: 'Language & Accessibility'),
                      _group([
                        _row(
                          icon: Icons.translate_rounded,
                          title: 'Language',
                          trailing: DropdownButton<String>(
                            value: settings.language,
                            underline: const SizedBox.shrink(),
                            items: _languageLabels.entries
                                .map((e) => DropdownMenuItem(value: e.key, child: Text(e.value)))
                                .toList(),
                            onChanged: (value) {
                              if (value != null && value != settings.language) _updateLanguage(value);
                            },
                          ),
                        ),
                        SwitchListTile(
                          secondary: _leadingIcon(Icons.contrast_rounded),
                          title: const Text('High contrast'),
                          subtitle: const Text('Stronger colours and borders for readability.'),
                          value: settings.highContrastEnabled,
                          onChanged: _updateHighContrast,
                        ),
                      ]),
                      if (settings.officerAvailabilityStatus != null) ...[
                        const JanSectionHeader(title: 'Availability'),
                        _group([
                          _row(
                            icon: Icons.badge_outlined,
                            title: 'Availability status',
                            // Audit GAP-038: the backend skips Busy/On leave officers when auto-assigning.
                            subtitle: 'Busy or On leave: no new complaints are auto-assigned to you',
                            trailing: DropdownButton<String>(
                              value: settings.officerAvailabilityStatus,
                              underline: const SizedBox.shrink(),
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
                        ]),
                      ],
                      const JanSectionHeader(title: 'Notifications'),
                      _group([
                        SwitchListTile(
                          secondary: _leadingIcon(Icons.sms_outlined),
                          title: const Text('SMS notifications'),
                          value: settings.notificationPreferences.smsEnabled,
                          onChanged: (value) => _updateNotificationPreference(smsEnabled: value),
                        ),
                        SwitchListTile(
                          secondary: _leadingIcon(Icons.notifications_active_outlined),
                          title: const Text('Push notifications'),
                          subtitle: const Text('Not yet available - saved for a future update.'),
                          value: settings.notificationPreferences.pushEnabled,
                          onChanged: (value) => _updateNotificationPreference(pushEnabled: value),
                        ),
                        SwitchListTile(
                          secondary: _leadingIcon(Icons.email_outlined),
                          title: const Text('Email notifications'),
                          subtitle: const Text('Status updates are always emailed, regardless of this setting.'),
                          value: settings.notificationPreferences.emailEnabled,
                          onChanged: (value) => _updateNotificationPreference(emailEnabled: value),
                        ),
                      ]),
                      const JanSectionHeader(title: 'Legal'),
                      _group([
                        ListTile(
                          leading: _leadingIcon(Icons.privacy_tip_outlined),
                          title: const Text('Privacy & Data Use'),
                          trailing: const Icon(Icons.chevron_right_rounded),
                          onTap: _openPrivacy,
                        ),
                        ListTile(
                          leading: _leadingIcon(Icons.description_outlined),
                          title: const Text('Terms of Use'),
                          trailing: const Icon(Icons.chevron_right_rounded),
                          onTap: _openTerms,
                        ),
                      ]),
                      const JanSectionHeader(title: 'Account'),
                      _group([
                        ListTile(
                          leading: _leadingIcon(Icons.person_outline_rounded),
                          title: const Text('My Profile'),
                          subtitle: const Text('Name, ward and sign-in details'),
                          trailing: const Icon(Icons.chevron_right_rounded),
                          onTap: _openProfile,
                        ),
                        ListTile(
                          leading: _leadingIcon(Icons.logout_rounded, color: JanColors.error, tint: JanColors.errorLight),
                          title: const Text(
                            'Log out of all devices',
                            style: TextStyle(color: JanColors.error, fontWeight: FontWeight.w700),
                          ),
                          subtitle: const Text('Ends every active session, including this one.'),
                          onTap: _logoutFromAllDevices,
                        ),
                      ]),
                    ],
                  ),
                ),
              ),
              if (_saving)
                const Positioned(
                  left: 0,
                  right: 0,
                  top: 0,
                  child: LinearProgressIndicator(semanticsLabel: 'Saving settings'),
                ),
            ],
          );
        },
      ),
    );
  }

  Widget _profileHeader(UserProfile profile) {
    final initials = profile.fullName
        .trim()
        .split(RegExp(r'\s+'))
        .where((p) => p.isNotEmpty)
        .take(2)
        .map((p) => p[0].toUpperCase())
        .join();
    return Padding(
      padding: const EdgeInsets.only(top: JanSpace.xs),
      child: JanCard(
        gradient: const LinearGradient(colors: [JanColors.navy, JanColors.navyDeep]),
        elevated: false,
        onTap: _openProfile,
        child: Semantics(
          label: '${profile.fullName}, ${_roleLabel(profile.role)}. Open My Profile',
          excludeSemantics: true,
          child: Row(
            children: [
              CircleAvatar(
                radius: 28,
                backgroundColor: JanColors.white.withValues(alpha: 0.16),
                child: Text(
                  initials.isEmpty ? '?' : initials,
                  style: const TextStyle(color: JanColors.white, fontSize: 20, fontWeight: FontWeight.w800),
                ),
              ),
              const SizedBox(width: JanSpace.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      profile.fullName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: JanColors.white, fontSize: 18, fontWeight: FontWeight.w800),
                    ),
                    const SizedBox(height: 4),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
                      decoration: BoxDecoration(
                        color: JanColors.white.withValues(alpha: 0.14),
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Text(
                        _roleLabel(profile.role),
                        style: const TextStyle(color: JanColors.white, fontSize: 12.5, fontWeight: FontWeight.w700),
                      ),
                    ),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right_rounded, color: JanColors.white),
            ],
          ),
        ),
      ),
    );
  }

  Widget _group(List<Widget> children) {
    return JanCard(
      padding: const EdgeInsets.symmetric(vertical: JanSpace.xxs),
      child: Column(
        children: [
          for (var i = 0; i < children.length; i++) ...[
            if (i > 0) const Divider(height: 1, indent: 64),
            children[i],
          ],
        ],
      ),
    );
  }

  Widget _row({required IconData icon, required String title, required Widget trailing, String? subtitle}) {
    return ListTile(
        leading: _leadingIcon(icon),
        title: Text(title),
        subtitle: subtitle == null ? null : Text(subtitle),
        trailing: trailing);
  }

  Widget _leadingIcon(IconData icon, {Color color = JanColors.primary, Color tint = JanColors.infoLight}) {
    return Container(
      width: 38,
      height: 38,
      decoration: BoxDecoration(color: tint, borderRadius: JanRadius.smAll),
      child: Icon(icon, color: color, size: 20),
    );
  }
}
