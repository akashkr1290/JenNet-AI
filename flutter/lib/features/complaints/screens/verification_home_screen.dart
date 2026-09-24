import 'package:flutter/material.dart';

import '../../auth/auth_api.dart';
import '../../auth/screens/login_screen.dart';
import '../../notifications/screens/notifications_screen.dart';
import '../../settings/screens/personal_settings_screen.dart';
import 'appeals_review_screen.dart';
import 'verification_queue_screen.dart';

/// Gap-backlog Patches 14/25/33 (Sep 2026 strict recheck): VERIFICATION_TEAM
/// had no home route at all - resolveHomeScreen fell through to the CITIZEN
/// shell, so the verification queue built earlier was unreachable. This shell
/// holds the verification queue and the appeal review queue.
class VerificationHomeScreen extends StatelessWidget {
  const VerificationHomeScreen({super.key});

  Future<void> _logout(BuildContext context) async {
    await AuthApi.instance.logout();
    if (!context.mounted) return;
    Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => const LoginScreen()));
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Verification'),
          bottom: const TabBar(tabs: [Tab(text: 'AI Review Queue'), Tab(text: 'Appeals')]),
          actions: [
            IconButton(
              onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const NotificationsScreen())),
              icon: const Icon(Icons.notifications_outlined),
              tooltip: 'Notifications',
            ),
            IconButton(
              onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const PersonalSettingsScreen())),
              icon: const Icon(Icons.settings_outlined),
              tooltip: 'Settings',
            ),
            IconButton(onPressed: () => _logout(context), icon: const Icon(Icons.logout), tooltip: 'Sign out'),
          ],
        ),
        body: const SafeArea(
          child: TabBarView(children: [
            VerificationQueueScreen(embedded: true),
            AppealsReviewScreen(embedded: true),
          ]),
        ),
      ),
    );
  }
}
