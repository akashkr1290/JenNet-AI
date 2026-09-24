import 'package:flutter/material.dart';

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

  @override
  Widget build(BuildContext context) {
    const screens = [OfficerDashboardScreen(), OfficerQueueScreen()];
    const titles = ['My Dashboard', 'My Queue'];
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
            tooltip: 'Settings',
          ),
          IconButton(onPressed: _logout, icon: const Icon(Icons.logout), tooltip: 'Sign out'),
        ],
      ),
      body: SafeArea(child: screens[_tab]),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) => setState(() => _tab = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.dashboard_outlined), label: 'Dashboard'),
          NavigationDestination(icon: Icon(Icons.inbox_outlined), label: 'Queue'),
        ],
      ),
    );
  }
}
