import 'package:flutter/material.dart';

import '../../../core/l10n/app_strings.dart';
import '../../../core/widgets/jan_shell.dart';
import '../../auth/auth_api.dart';
import '../../auth/screens/login_screen.dart';
import '../../dashboard/screens/citizen_dashboard_screen.dart';
import '../../dashboard/screens/community_heatmap_screen.dart';
import '../../notifications/screens/notifications_screen.dart';
import '../../settings/screens/personal_settings_screen.dart';
import '../pending_submission_sync.dart';
import 'complaint_list_screen.dart';
import 'complaint_submission_screen.dart';

/// Citizen home shell. Gap-backlog strict recheck (Sep 2026): now four tabs -
/// Dashboard (Patch 08), Submit, My Complaints, and Community heatmap
/// (Patch 12, previously built but unreachable) - with labels from
/// AppStrings so the saved EN/HI language setting takes effect (Patch 47).
class ComplaintHomeScreen extends StatefulWidget {
  const ComplaintHomeScreen({super.key});

  @override
  State<ComplaintHomeScreen> createState() => _ComplaintHomeScreenState();
}

class _ComplaintHomeScreenState extends State<ComplaintHomeScreen> {
  int _tab = 0;

  // Gap-backlog Patch 45: automatic upload of complaints queued while offline.
  @override
  void initState() {
    super.initState();
    PendingSubmissionSync.instance.messages.addListener(_onSyncMessage);
    PendingSubmissionSync.instance.start();
  }

  @override
  void dispose() {
    PendingSubmissionSync.instance.messages.removeListener(_onSyncMessage);
    super.dispose();
  }

  void _onSyncMessage() {
    final message = PendingSubmissionSync.instance.messages.value;
    if (message == null || !mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
    PendingSubmissionSync.instance.messages.value = null;
  }

  Future<void> _logout() async {
    await AuthApi.instance.logout();
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => const LoginScreen()),
    );
  }

  void _openNotifications() =>
      Navigator.of(context).push(MaterialPageRoute(builder: (_) => const NotificationsScreen()));

  void _openSettings() =>
      Navigator.of(context).push(MaterialPageRoute(builder: (_) => const PersonalSettingsScreen()));

  // UI redesign: the shared adaptive JanShell (bottom navigation on phones,
  // navy side rail on web). Same four tabs, same localized labels.
  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<String>(
      valueListenable: AppStrings.currentLanguage,
      builder: (context, _, __) {
        return JanShell(
          roleLabel: 'Citizen',
          currentIndex: _tab,
          onDestinationSelected: (i) => setState(() => _tab = i),
          onLogout: _logout,
          actions: [
            JanShellAction(icon: Icons.notifications_outlined, tooltip: 'Notifications', onPressed: _openNotifications),
            JanShellAction(icon: Icons.settings_outlined, tooltip: AppStrings.of('nav_settings'), onPressed: _openSettings),
          ],
          destinations: [
            JanDestination(
              label: AppStrings.of('nav_dashboard'),
              icon: Icons.space_dashboard_outlined,
              selectedIcon: Icons.space_dashboard_rounded,
              builder: (_) => CitizenDashboardScreen(onReportIssue: () => setState(() => _tab = 1)),
            ),
            JanDestination(
              label: AppStrings.of('nav_submit'),
              icon: Icons.add_a_photo_outlined,
              selectedIcon: Icons.add_a_photo_rounded,
              heading: AppStrings.of('title_report_issue'),
              subheading: AppStrings.of('subtitle_report_issue'),
              builder: (_) => const ComplaintSubmissionScreen(),
            ),
            JanDestination(
              label: AppStrings.of('nav_my_complaints'),
              icon: Icons.list_alt_outlined,
              selectedIcon: Icons.list_alt_rounded,
              heading: AppStrings.of('nav_my_complaints'),
              builder: (_) => ComplaintListScreen(onSubmitComplaint: () => setState(() => _tab = 1)),
            ),
            JanDestination(
              label: AppStrings.of('nav_community'),
              icon: Icons.people_outline_rounded,
              selectedIcon: Icons.people_rounded,
              heading: AppStrings.of('nav_community'),
              subheading: AppStrings.of('subtitle_community'),
              builder: (_) => CommunityHeatmapScreen(embedded: true, onSubmitComplaint: () => setState(() => _tab = 1)),
            ),
          ],
        );
      },
    );
  }
}
