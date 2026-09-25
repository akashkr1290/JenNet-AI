import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/core/api/api_exception.dart';
import 'package:jannet_ai/core/widgets/jan_form_widgets.dart';
import 'package:jannet_ai/core/widgets/jan_shell.dart';
import 'package:jannet_ai/core/widgets/jan_stat_card.dart';
import 'package:jannet_ai/core/widgets/jan_states.dart';
import 'package:jannet_ai/features/complaints/models/complaint.dart';
import 'package:jannet_ai/features/complaints/widgets/status_timeline.dart';

/// UI redesign: widget tests for the shared JanNet design-system components.
///
/// NOT EXECUTED in the workspace that produced them (no Flutter/Dart SDK was
/// available there); written against the components' actual widget trees and
/// meant to run with `flutter test` in CI or locally.
void main() {
  Widget wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

  testWidgets('JanStatCard shows its value and label', (tester) async {
    await tester.pumpWidget(wrap(const JanStatCard(label: 'Pending', value: '8', icon: Icons.schedule)));
    expect(find.text('Pending'), findsOneWidget);
    expect(find.text('8'), findsOneWidget);
  });

  testWidgets('JanEmptyState action button calls back', (tester) async {
    var tapped = 0;
    await tester.pumpWidget(wrap(JanEmptyState(
      icon: Icons.inbox_outlined,
      title: 'No complaints yet',
      message: 'Submit an issue.',
      actionLabel: 'Submit Complaint',
      onAction: () => tapped++,
    )));
    expect(find.text('No complaints yet'), findsOneWidget);
    await tester.tap(find.text('Submit Complaint'));
    expect(tapped, 1);
  });

  testWidgets('JanErrorState.fromError maps a network error to the offline state', (tester) async {
    var retried = 0;
    await tester.pumpWidget(wrap(JanErrorState.fromError(
      ApiException.network(),
      fallback: 'Could not load.',
      onRetry: () => retried++,
    )));
    expect(find.text("You're offline"), findsOneWidget);
    await tester.tap(find.text('Check Connectivity'));
    expect(retried, 1);
  });

  testWidgets('JanErrorState.fromError uses the fallback for unknown errors', (tester) async {
    await tester.pumpWidget(wrap(JanErrorState.fromError(StateError('x'), fallback: 'Could not load.')));
    expect(find.text('Could not load.'), findsOneWidget);
    expect(find.byType(FilledButton), findsNothing);
  });

  testWidgets('JanOtpInput accepts digits only and reports a complete code once', (tester) async {
    final controller = TextEditingController();
    final completed = <String>[];
    await tester.pumpWidget(wrap(Padding(
      padding: const EdgeInsets.all(16),
      child: JanOtpInput(controller: controller, onCompleted: completed.add),
    )));

    await tester.enterText(find.byType(TextField), '12ab34');
    await tester.pump();
    expect(controller.text, '1234');
    expect(completed, isEmpty);

    await tester.enterText(find.byType(TextField), '123456');
    await tester.pump();
    expect(completed, ['123456']);

    // Focus changes / rebuilds with the same code must not report it again.
    await tester.pump();
    expect(completed, ['123456']);
    controller.dispose();
  });

  testWidgets('StatusTimeline orders entries oldest first and handles an empty history', (tester) async {
    await tester.pumpWidget(wrap(StatusTimeline(history: [
      StatusHistoryEntry(newStatus: 'IN_PROGRESS', actorType: 'OFFICER', changedAt: DateTime(2026, 9, 2)),
      StatusHistoryEntry(newStatus: 'SUBMITTED', actorType: 'CITIZEN', changedAt: DateTime(2026, 9, 1)),
    ])));
    final submitted = tester.getTopLeft(find.text('Submitted'));
    final inProgress = tester.getTopLeft(find.text('In Progress'));
    expect(submitted.dy, lessThan(inProgress.dy));

    await tester.pumpWidget(wrap(const StatusTimeline(history: [])));
    expect(find.text('No status updates yet.'), findsOneWidget);
  });

  testWidgets('JanShell switches destinations from the bottom navigation on phones', (tester) async {
    var index = 0;
    await tester.pumpWidget(MaterialApp(
      home: StatefulBuilder(
        builder: (context, setState) => JanShell(
          roleLabel: 'Officer',
          currentIndex: index,
          onDestinationSelected: (i) => setState(() => index = i),
          onLogout: () {},
          actions: const [],
          destinations: [
            JanDestination(label: 'Dashboard', icon: Icons.dashboard_outlined, builder: (_) => const Text('Page A')),
            JanDestination(label: 'Queue', icon: Icons.inbox_outlined, builder: (_) => const Text('Page B')),
          ],
        ),
      ),
    ));
    expect(find.byType(NavigationBar), findsOneWidget);
    expect(find.text('Page A'), findsOneWidget);
    await tester.tap(find.text('Queue').first);
    await tester.pumpAndSettle();
    expect(find.text('Page B'), findsOneWidget);
    expect(find.byTooltip('Sign out'), findsOneWidget);
  });

  testWidgets('JanShell uses a side rail instead of a bottom bar on wide screens', (tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(
      home: JanShell(
        roleLabel: 'Officer',
        currentIndex: 0,
        onDestinationSelected: (_) {},
        onLogout: () {},
        actions: const [],
        destinations: [
          JanDestination(label: 'Dashboard', icon: Icons.dashboard_outlined, builder: (_) => const Text('Page A')),
        ],
      ),
    ));
    expect(find.byType(NavigationBar), findsNothing);
    expect(find.text('OFFICER'), findsOneWidget);
    expect(find.text('Sign out'), findsOneWidget);
  });
}
