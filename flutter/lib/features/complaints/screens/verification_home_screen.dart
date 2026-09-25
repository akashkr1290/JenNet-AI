import 'package:flutter/material.dart';

import '../../../core/widgets/jan_shell.dart';
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
///
/// UI redesign: the two queues are now destinations of the shared adaptive
/// JanShell (bottom navigation on phones, side rail on web) instead of a
/// TabBar; the screens, actions and sign-out are unchanged.
class VerificationHomeScreen extends StatefulWidget {
  const VerificationHomeScreen({super.key});

  @override
  State<VerificationHomeScreen> createState() => _VerificationHomeScreenState();
}

class _VerificationHomeScreenState extends State<VerificationHomeScreen> {
  int _tab = 0;

  Future<void> _logout() async {
    await AuthApi.instance.logout();
    if (!mounted) return;
    Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => const LoginScreen()));
  }

  @override
  Widget build(BuildContext context) {
    return JanShell(
      roleLabel: 'Verification Team',
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
          label: 'AI Review Queue',
          icon: Icons.fact_check_outlined,
          selectedIcon: Icons.fact_check_rounded,
          heading: 'AI Review Queue',
          subheading: 'Low-confidence AI results waiting for a human decision',
          builder: (_) => const VerificationQueueScreen(embedded: true),
        ),
        JanDestination(
          label: 'Appeals',
          icon: Icons.gavel_outlined,
          selectedIcon: Icons.gavel_rounded,
          heading: 'Appeals',
          subheading: 'Citizen appeals against rejected complaints',
          builder: (_) => const AppealsReviewScreen(embedded: true),
        ),
      ],
    );
  }
}
