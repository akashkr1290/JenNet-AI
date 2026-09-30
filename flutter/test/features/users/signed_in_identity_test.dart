import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/core/platform_status.dart';
import 'package:jannet_ai/core/widgets/jan_shell.dart';
import 'package:jannet_ai/features/users/user_api.dart';
import 'package:jannet_ai/features/users/widgets/signed_in_identity.dart';

/// Pilot 2026-09-30: the side bar says who is signed in and for which department.
void main() {
  UserProfile profile(Map<String, dynamic> extra) => UserProfile.fromJson({
        'userId': 7,
        'fullName': 'Ravi Kumar',
        'role': 'DEPARTMENT_HEAD',
        ...extra,
      });

  test('reads department and ward names', () {
    final p = profile({'departmentId': 3, 'departmentName': 'Public Works', 'wardName': 'Ward 12'});
    expect(p.departmentName, 'Public Works');
    expect(p.wardName, 'Ward 12');
    expect(p.roleAndUnit, 'Department Head · Public Works');
  });

  test('citizens show their ward, admins only their role, old payloads still parse', () {
    expect(profile({'role': 'CITIZEN', 'wardName': 'Ward 12'}).roleAndUnit, 'Citizen · Ward 12');
    expect(profile({'role': 'SUPER_ADMIN'}).roleAndUnit, 'Super Administrator');
    expect(profile({'role': 'GOVERNMENT_OFFICER', 'departmentId': 3}).roleAndUnit, 'Government Officer');
  });

  testWidgets('shows name and role with department once loaded', (tester) async {
    final p = profile({'departmentId': 3, 'departmentName': 'Public Works'});
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SignedInIdentity(fallbackRole: 'Department Head', profile: Future.value(p))),
    ));
    expect(find.text('DEPARTMENT HEAD'), findsOneWidget); // before the profile arrives
    await tester.pump();
    expect(find.text('Ravi Kumar'), findsOneWidget);
    expect(find.text('DEPARTMENT HEAD · PUBLIC WORKS'), findsOneWidget);
  });

  testWidgets('keeps the plain role when the profile cannot be loaded', (tester) async {
    final failing = Future<UserProfile>.error('offline')..ignore();
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: SignedInIdentity(fallbackRole: 'Department Head', profile: failing),
      ),
    ));
    await tester.pump();
    expect(find.text('DEPARTMENT HEAD'), findsOneWidget);
  });

  testWidgets('the wide side rail shows the identity instead of the bare role', (tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final p = profile({'departmentId': 3, 'departmentName': 'Public Works'});
    await tester.pumpWidget(MaterialApp(
      home: JanShell(
        roleLabel: 'Department Head',
        identity: SignedInIdentity(fallbackRole: 'Department Head', profile: Future.value(p)),
        currentIndex: 0,
        onDestinationSelected: (_) {},
        onLogout: () {},
        actions: const [],
        destinations: [
          JanDestination(label: 'Queue', icon: Icons.inbox_outlined, builder: (_) => const Text('Page A')),
        ],
      ),
    ));
    await tester.pump();
    expect(find.text('Ravi Kumar'), findsOneWidget);
    expect(find.text('DEPARTMENT HEAD · PUBLIC WORKS'), findsOneWidget);
    PlatformStatusService.instance.stop(); // the shell's status banner starts a 5-minute poll
  });
}
