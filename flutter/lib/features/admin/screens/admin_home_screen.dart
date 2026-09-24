import 'package:flutter/material.dart';

import '../../auth/auth_api.dart';
import '../../auth/screens/login_screen.dart';
import '../../dashboard/screens/government_dashboard_screen.dart';
import '../../notifications/screens/notifications_screen.dart';
import '../../settings/screens/personal_settings_screen.dart';
import 'admin_audit_log_screen.dart';
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
class AdminHomeScreen extends StatelessWidget {
  const AdminHomeScreen({super.key});

  Future<void> _logout(BuildContext context) async {
    await AuthApi.instance.logout();
    if (!context.mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => const LoginScreen()),
    );
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 5,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Admin'),
          bottom: const TabBar(
            isScrollable: true,
            tabs: [
              Tab(text: 'Dashboard'),
              Tab(text: 'Users'),
              Tab(text: 'Settings'),
              Tab(text: 'Routing Rules'),
              Tab(text: 'Audit Log'),
            ],
          ),
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
              icon: const Icon(Icons.manage_accounts_outlined),
              // Distinct label from the "Settings" tab below (Phase 14
              // platform-wide thresholds) - this opens this Admin's own
              // personal settings (Phase 15), not a platform config screen.
              tooltip: 'My Settings',
            ),
            IconButton(onPressed: () => _logout(context), icon: const Icon(Icons.logout), tooltip: 'Sign out'),
          ],
        ),
        body: const SafeArea(
          child: TabBarView(
            children: [
              GovernmentDashboardScreen(showJurisdictionSections: true),
              AdminUserManagementScreen(),
              AdminSettingsScreen(),
              AdminRoutingRulesScreen(),
              AdminAuditLogScreen(),
            ],
          ),
        ),
      ),
    );
  }
}
