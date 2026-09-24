import 'package:flutter/material.dart';

import '../../../core/l10n/app_strings.dart';
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

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<String>(
      valueListenable: AppStrings.currentLanguage,
      builder: (context, _, __) {
        const screens = [
          CitizenDashboardScreen(),
          ComplaintSubmissionScreen(),
          ComplaintListScreen(),
          CommunityHeatmapScreen(embedded: true),
        ];
        final titles = [
          AppStrings.of('nav_dashboard'),
          AppStrings.of('action_submit_complaint'),
          AppStrings.of('nav_my_complaints'),
          AppStrings.of('title_community_heatmap'),
        ];
        return Scaffold(
          appBar: AppBar(
            title: Text(titles[_tab]),
            actions: [
              IconButton(
                onPressed: () => Navigator.of(context)
                    .push(MaterialPageRoute(builder: (_) => const NotificationsScreen())),
                icon: const Icon(Icons.notifications_outlined),
                tooltip: 'Notifications',
              ),
              IconButton(
                onPressed: () => Navigator.of(context)
                    .push(MaterialPageRoute(builder: (_) => const PersonalSettingsScreen())),
                icon: const Icon(Icons.settings_outlined),
                tooltip: AppStrings.of('nav_settings'),
              ),
              IconButton(onPressed: _logout, icon: const Icon(Icons.logout), tooltip: 'Sign out'),
            ],
          ),
          body: SafeArea(child: screens[_tab]),
          bottomNavigationBar: NavigationBar(
            selectedIndex: _tab,
            onDestinationSelected: (i) => setState(() => _tab = i),
            destinations: [
              NavigationDestination(icon: const Icon(Icons.dashboard_outlined), label: AppStrings.of('nav_dashboard')),
              NavigationDestination(icon: const Icon(Icons.add_a_photo_outlined), label: AppStrings.of('nav_submit')),
              NavigationDestination(icon: const Icon(Icons.list_alt_outlined), label: AppStrings.of('nav_my_complaints')),
              NavigationDestination(icon: const Icon(Icons.map_outlined), label: AppStrings.of('nav_community')),
            ],
          ),
        );
      },
    );
  }
}
