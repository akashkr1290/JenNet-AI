import 'package:flutter/material.dart';

import '../core/app_preferences.dart';
import '../core/auth/session.dart';
import 'admin/screens/admin_home_screen.dart';
import 'complaints/screens/complaint_home_screen.dart';
import 'complaints/screens/department_head_home_screen.dart';
import 'complaints/screens/officer_home_screen.dart';
import 'complaints/screens/verification_home_screen.dart';
import 'settings/settings_api.dart';

/// Phase 12 addition: both the app's startup gate (main.dart, an already-
/// logged-in session) and the post-login navigation (login_screen.dart, a
/// fresh session) need the exact same "which home screen for this role"
/// decision - factored out once here rather than duplicated in both
/// places. See Session's Javadoc-equivalent comment: this is convenience
/// routing only, never a security boundary.
///
/// Phase 13 fix: the `session.dart` import above was
/// `'../auth/session.dart'` (resolving to the non-existent
/// `lib/features/auth/session.dart` / `lib/auth/session.dart` depending
/// on how it's read - the real file is `lib/core/auth/session.dart`), a
/// pre-existing broken import from Phase 12 that would have failed to
/// resolve at build time. Corrected here rather than left in place, since
/// this file needed touching for Phase 13's own routing change anyway
/// (see PROJECT_INTEGRATION.md Section 6 for the decision record).
///
/// Phase 13 addition: DEPARTMENT_HEAD now gets its own home shell
/// (DepartmentHeadHomeScreen: Queue + Performance tabs) instead of
/// sharing OfficerHomeScreen's single-tab shell with GOVERNMENT_OFFICER -
/// see DepartmentHeadHomeScreen's Javadoc-equivalent comment for why they
/// weren't merged into one role-branching screen instead.
/// Phase 14 (Admin & Settings Module) addition: ADMIN/SUPER_ADMIN now get
/// their own home shell (AdminHomeScreen: Users/Settings/Routing
/// Rules/Audit Log tabs) instead of falling through to
/// ComplaintHomeScreen (the citizen-only default) - same reasoning as
/// Phase 13's DEPARTMENT_HEAD branch above: a distinct role gets a
/// distinct shell rather than a shared screen branching internally.
Future<Widget> resolveHomeScreen() async {
  _loadPreferences();
  final role = await Session.instance.role;
  if (role == 'ADMIN' || role == 'SUPER_ADMIN') return const AdminHomeScreen();
  if (role == 'DEPARTMENT_HEAD') return const DepartmentHeadHomeScreen();
  // Gap-backlog strict recheck: VERIFICATION_TEAM and MAINTENANCE_TEAM used to
  // fall through to the CITIZEN shell below.
  if (role == 'VERIFICATION_TEAM') return const VerificationHomeScreen();
  if (role == 'GOVERNMENT_OFFICER' || role == 'MAINTENANCE_TEAM') return const OfficerHomeScreen();
  return const ComplaintHomeScreen();
}

/// Gap-backlog Patches 46/47: apply the signed-in user's saved high-contrast
/// and language settings. Best effort - a failure leaves the defaults.
Future<void> _loadPreferences() async {
  try {
    AppPreferences.apply(await SettingsApi.instance.getSettings());
  } catch (_) {}
}
