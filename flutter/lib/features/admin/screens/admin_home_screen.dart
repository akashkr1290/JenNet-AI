import 'package:flutter/material.dart';

import '../../../core/widgets/jan_shell.dart';
import '../../auth/auth_api.dart';
import '../../auth/screens/login_screen.dart';
import '../../dashboard/screens/government_dashboard_screen.dart';
import '../../notifications/screens/notifications_screen.dart';
import '../../reports/screens/period_report_screen.dart';
import '../../settings/screens/personal_settings_screen.dart';
import 'admin_audit_log_screen.dart';
import 'admin_configuration_screen.dart';
import 'admin_routing_rules_screen.dart';
import 'admin_settings_screen.dart';
import 'admin_user_management_screen.dart';

/// Phase 14 (Admin & Settings Module) home shell for ADMIN/SUPER_ADMIN
/// accounts - the Admin equivalent of DepartmentHeadHomeScreen (Phase
/// 13). Four tabs, one per Phase 14 backend controller: Users
/// (AdminUserController), Settings (AdminSettingsController), Routing
/// Rules (AdminRoutingRuleController's Phase 14 additions - rule
/// *creation* has existed since Phase 11 with no Flutter caller until
/// now), Audit Log (AdminAuditLogController). Kept as a single shell with
/// four tabs rather than four standalone screens - unlike
/// DepartmentHeadHomeScreen's reasoning for splitting from
/// OfficerHomeScreen, there's no role that should see only some of these
/// tabs (ADMIN and SUPER_ADMIN see the same tab set; the finer per-action
/// SUPER_ADMIN-only rules are enforced inline within each tab, e.g. the
/// "Add User" role dropdown only offering ADMIN to a SUPER_ADMIN caller).
///
/// Phase 16 addition: a fifth tab, "Dashboard" (SRS 16.3 "Government
/// Dashboard (Overview)" + SRS 24.3 "Admin Dashboard"), placed first
/// since it's the jurisdiction-wide landing view an Admin/Super Admin
/// most likely wants on opening this screen - unlike the other four
/// tabs (all administrative *actions*), this one is a read-only
/// overview, so leading with it doesn't disrupt the existing tab order's
/// meaning for the other four. Uses `departmentId: null` +
/// `showJurisdictionSections: true` (unlike DepartmentHeadHomeScreen's
/// own use of this same widget) - see GovernmentDashboardScreen's
/// Javadoc-equivalent comment for exactly what that unlocks (department
/// comparison chart + full Admin summary, both ADMIN/SUPER_ADMIN-only
/// server-side regardless of what this screen sends).
///
/// UI redesign: the five tabs are destinations of the shared adaptive
/// JanShell (bottom navigation on phones, navy side rail on web) instead of
/// a scrollable TabBar; screens, actions and sign-out are unchanged.
class AdminHomeScreen extends StatefulWidget {
  const AdminHomeScreen({super.key});

  @override
  State<AdminHomeScreen> createState() => _AdminHomeScreenState();
}

class _AdminHomeScreenState extends State<AdminHomeScreen> {
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
    return JanShell(
      roleLabel: 'Administrator',
      currentIndex: _tab,
      onDestinationSelected: (i) => setState(() => _tab = i),
      onLogout: _logout,
      actions: [
        // Audit GAP-039: daily / weekly / custom-range reports.
        JanShellAction(
          icon: Icons.summarize_outlined,
          tooltip: 'Reports',
          onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const PeriodReportScreen())),
        ),
        JanShellAction(
          icon: Icons.notifications_outlined,
          tooltip: 'Notifications',
          onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const NotificationsScreen())),
        ),
        // Distinct label from the "Settings" destination (Phase 14
        // platform-wide thresholds) - this opens this Admin's own personal
        // settings (Phase 15), not a platform config screen.
        JanShellAction(
          icon: Icons.manage_accounts_outlined,
          tooltip: 'My Settings',
          onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const PersonalSettingsScreen())),
        ),
      ],
      destinations: [
        JanDestination(
          label: 'Dashboard',
          icon: Icons.space_dashboard_outlined,
          selectedIcon: Icons.space_dashboard_rounded,
          heading: 'Admin Dashboard',
          builder: (_) => const GovernmentDashboardScreen(showJurisdictionSections: true),
        ),
        JanDestination(
          label: 'Users',
          icon: Icons.group_outlined,
          selectedIcon: Icons.group_rounded,
          heading: 'User Management',
          builder: (_) => const AdminUserManagementScreen(),
        ),
        JanDestination(
          label: 'Settings',
          icon: Icons.tune_outlined,
          selectedIcon: Icons.tune_rounded,
          heading: 'Platform Settings',
          builder: (_) => const AdminSettingsScreen(),
        ),
        // Audit GAP-020/031/033/037 (SRS 15.11): departments, wards, sensitive
        // zones, out-of-jurisdiction review, maintenance mode and announcement.
        JanDestination(
          label: 'Setup',
          icon: Icons.domain_outlined,
          selectedIcon: Icons.domain_rounded,
          heading: 'Municipal Setup',
          builder: (_) => const AdminConfigurationScreen(),
        ),
        JanDestination(
          label: 'Routing',
          icon: Icons.alt_route_outlined,
          selectedIcon: Icons.alt_route_rounded,
          heading: 'Routing Rules',
          builder: (_) => const AdminRoutingRulesScreen(),
        ),
        JanDestination(
          label: 'Audit Log',
          icon: Icons.history_outlined,
          selectedIcon: Icons.history_rounded,
          heading: 'Audit Log',
          builder: (_) => const AdminAuditLogScreen(),
        ),
      ],
    );
  }
}
