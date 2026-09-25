import 'package:flutter/material.dart';

import '../../../core/widgets/jan_shell.dart';
import '../../auth/auth_api.dart';
import '../../auth/screens/login_screen.dart';
import '../../dashboard/screens/officer_dashboard_screen.dart';
import '../../notifications/screens/notifications_screen.dart';
import '../../settings/screens/personal_settings_screen.dart';
import 'officer_queue_screen.dart';

/// Officer home shell (GOVERNMENT_OFFICER, and MAINTENANCE_TEAM since the Sep
/// 2026 strict recheck). Was a single "My Queue" view; Gap-backlog Patch 09
/// adds the Dashboard tab (workload, SLA status, today's tasks, performance).
class OfficerHomeScreen extends StatefulWidget {
  const OfficerHomeScreen({super.key});

  @override
  State<OfficerHomeScreen> createState() => _OfficerHomeScreenState();
}

class _OfficerHomeScreenState extends State<OfficerHomeScreen> {
  int _tab = 0;

  Future<void> _logout() async {
    await AuthApi.instance.logout();
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => const LoginScreen()),
    );
  }

  // UI redesign: shared adaptive JanShell (bottom navigation on phones,
  // navy side rail on web). Same two tabs, actions and sign-out.
  @override
  Widget build(BuildContext context) {
    return JanShell(
      roleLabel: 'Field Officer',
      currentIndex: _tab,
      onDestinationSelected: (i) => setState(() => _tab = i),
      onLogout: _logout,
      actions: [
        JanShellAction(
          icon: Icons.notifications_outlined,
          tooltip: 'Notifications',
          onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const NotificationsScreen())),
        ),
        JanShellAction(
          icon: Icons.settings_outlined,
          tooltip: 'Settings',
          onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const PersonalSettingsScreen())),
        ),
      ],
      destinations: [
        JanDestination(
          label: 'Dashboard',
          icon: Icons.space_dashboard_outlined,
          selectedIcon: Icons.space_dashboard_rounded,
          heading: 'My Dashboard',
          builder: (_) => const OfficerDashboardScreen(),
        ),
        JanDestination(
          label: 'Queue',
          icon: Icons.inbox_outlined,
          selectedIcon: Icons.inbox_rounded,
          heading: 'My Queue',
          subheading: 'Complaints assigned to you',
          builder: (_) => const OfficerQueueScreen(),
        ),
      ],
    );
  }
}
