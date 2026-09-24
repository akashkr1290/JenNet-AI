import 'appeals_review_screen.dart';
import 'package:flutter/material.dart';

import '../../auth/auth_api.dart';
import '../../auth/screens/login_screen.dart';
import '../../dashboard/screens/government_dashboard_screen.dart';
import '../../department/screens/department_performance_screen.dart';
import '../../notifications/screens/notifications_screen.dart';
import '../../settings/screens/personal_settings_screen.dart';
import '../../users/user_api.dart';
import '../screens/officer_queue_screen.dart';

/// Phase 13 (Department Head Module) home shell for DEPARTMENT_HEAD
/// accounts - the Department Head equivalent of OfficerHomeScreen (Phase
/// 12, GOVERNMENT_OFFICER-only). Two tabs: "Queue" (reuses
/// OfficerQueueScreen as-is - ComplaintService.list already scopes a
/// DEPARTMENT_HEAD to their whole department's queue server-side, Phase
/// 12) and "Performance" (new this phase, SRS 16.2 "Department
/// Performance View"). Kept as a separate screen from OfficerHomeScreen
/// rather than one screen branching on role/tab-count - a plain
/// GOVERNMENT_OFFICER must never see the Performance tab (they have no
/// SRS-documented department-wide oversight permission), and the
/// Performance tab needs the signed-in user's own departmentId (fetched
/// once here via GET /users/me - see UserApi's Javadoc) that
/// OfficerHomeScreen has no reason to ever fetch.
///
/// Phase 16 addition: a third "Dashboard" tab (SRS 16.3 "Government
/// Dashboard (Overview)" screen - permission line: "Department Head,
/// Admin, Super Admin"), reusing the same fetched `departmentId` this
/// screen already loads for the Performance tab. Deliberately distinct
/// from "Performance": Performance (Phase 13) is officer-workload/SLA-
/// compliance-focused with a "Reassign Officer" action; Dashboard (Phase
/// 16) is the broader KPI/heatmap/category-trend view with no per-
/// officer breakdown and no reassignment action - see
/// GovernmentDashboardScreen's Javadoc for the full scope split.
class DepartmentHeadHomeScreen extends StatefulWidget {
  const DepartmentHeadHomeScreen({super.key});

  @override
  State<DepartmentHeadHomeScreen> createState() => _DepartmentHeadHomeScreenState();
}

class _DepartmentHeadHomeScreenState extends State<DepartmentHeadHomeScreen> {
  late final Future<UserProfile> _selfFuture;

  @override
  void initState() {
    super.initState();
    _selfFuture = UserApi.instance.me();
  }

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
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Department Head'),
          bottom: const TabBar(
            isScrollable: true,
            tabs: [
              Tab(text: 'Queue'),
              Tab(text: 'Performance'),
              Tab(text: 'Dashboard'),
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
              icon: const Icon(Icons.settings_outlined),
              tooltip: 'Settings',
            ),
            IconButton(
              // Gap-backlog Patch 14: department heads review citizen appeals.
              onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const AppealsReviewScreen())),
              icon: const Icon(Icons.gavel_outlined),
              tooltip: 'Pending Appeals',
            ),
            IconButton(onPressed: () => _logout(context), icon: const Icon(Icons.logout), tooltip: 'Sign out'),
          ],
        ),
        body: SafeArea(
          child: TabBarView(
            children: [
              const OfficerQueueScreen(),
              _departmentScopedTab(
                (departmentId) => DepartmentPerformanceScreen(departmentId: departmentId),
                noDepartmentMessage: 'No department is assigned to this account yet - there is nothing to show a '
                    'performance view for. Contact an Admin to complete account provisioning.',
              ),
              _departmentScopedTab(
                (departmentId) => GovernmentDashboardScreen(departmentId: departmentId),
                noDepartmentMessage: 'No department is assigned to this account yet - there is nothing to show a '
                    'dashboard for. Contact an Admin to complete account provisioning.',
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Phase 16 addition: factored out of what was previously the
  /// Performance tab's inline `FutureBuilder` (Phase 13) so the new
  /// Dashboard tab doesn't duplicate the identical "no department
  /// assigned yet" empty-state handling - both tabs need the exact same
  /// `_selfFuture`-derived `departmentId` before they can render
  /// anything real.
  Widget _departmentScopedTab(
    Widget Function(int departmentId) builder, {
    required String noDepartmentMessage,
  }) {
    return FutureBuilder<UserProfile>(
      future: _selfFuture,
      builder: (context, snapshot) {
        if (snapshot.connectionState == ConnectionState.waiting) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError || !snapshot.hasData || snapshot.data!.departmentId == null) {
          return Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Text(noDepartmentMessage, textAlign: TextAlign.center),
            ),
          );
        }
        return builder(snapshot.data!.departmentId!);
      },
    );
  }
}
