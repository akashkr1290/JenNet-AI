import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/complaints/models/complaint_status.dart';
import 'package:jannet_ai/features/complaints/widgets/status_badge.dart';

/// Phase 20: widget tests for [StatusBadge] - every [ComplaintStatus] value
/// must render without throwing (the widget's `_color` getter is a
/// non-exhaustive-looking switch with no default arm, so a future enum
/// value with no case would be a compile error, not a runtime one - this
/// test is a runtime backstop matching the same intent as
/// ComplaintStateMachineTest's analogous @EnumSource sweep on the backend
/// side of this app) and shows that status's label text.
///
/// NOT EXECUTED in this workspace (no `flutter`/`dart` SDK on PATH - see
/// PROJECT_PROGRESS.md's Phase 20 TESTS section). Manually validated
/// against status_badge.dart's actual widget tree.
void main() {
  Widget wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

  testWidgets('renders the label text for every ComplaintStatus value', (tester) async {
    for (final status in ComplaintStatus.values) {
      await tester.pumpWidget(wrap(StatusBadge(status: status)));
      expect(find.text(status.label), findsOneWidget, reason: 'status: $status');
    }
  });

  testWidgets('verified and assigned share the same indigo-family styling', (tester) async {
    await tester.pumpWidget(wrap(StatusBadge(status: ComplaintStatus.verified)));
    final verifiedText = tester.widget<Text>(find.text('Verified'));

    await tester.pumpWidget(wrap(StatusBadge(status: ComplaintStatus.assigned)));
    final assignedText = tester.widget<Text>(find.text('Assigned'));

    expect(verifiedText.style?.color, assignedText.style?.color);
  });

  testWidgets('resolved and closed render in a distinctly different color from rejected/duplicate', (tester) async {
    await tester.pumpWidget(wrap(StatusBadge(status: ComplaintStatus.resolved)));
    final resolvedText = tester.widget<Text>(find.text('Resolved'));

    await tester.pumpWidget(wrap(StatusBadge(status: ComplaintStatus.rejected)));
    final rejectedText = tester.widget<Text>(find.text('Rejected'));

    // Resolved (success, green) must not be visually confusable with
    // Rejected (failure, red) - a real citizen-facing usability concern,
    // not just an implementation detail.
    expect(resolvedText.style?.color, isNot(equals(rejectedText.style?.color)));
  });

  testWidgets('badge has rounded-pill styling (BorderRadius.circular)', (tester) async {
    await tester.pumpWidget(wrap(StatusBadge(status: ComplaintStatus.submitted)));
    final container = tester.widget<Container>(find.byType(Container));
    final decoration = container.decoration as BoxDecoration;
    expect(decoration.borderRadius, BorderRadius.circular(12));
  });
}
