import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/onboarding/onboarding_screen.dart';

/// Post-UI gap fix: first-launch onboarding. NOT EXECUTED in the workspace
/// that wrote it (no Flutter SDK there) - run with `flutter test`.
void main() {
  testWidgets('Next walks through the pages and Get Started finishes', (tester) async {
    var finished = 0;
    await tester.pumpWidget(MaterialApp(home: OnboardingScreen(onFinished: () => finished++)));

    expect(find.text('Report Civic Issues'), findsOneWidget);
    expect(find.text('Skip'), findsOneWidget);

    await tester.tap(find.text('Next'));
    await tester.pumpAndSettle();
    expect(find.text('Track Every Update'), findsOneWidget);

    await tester.tap(find.text('Next'));
    await tester.pumpAndSettle();
    expect(find.text('Improve Your Community'), findsOneWidget);
    expect(find.text('Skip'), findsNothing);
    expect(finished, 0);

    await tester.tap(find.text('Get Started'));
    await tester.pump();
    expect(finished, 1);
  });

  testWidgets('Skip finishes onboarding from the first page', (tester) async {
    var finished = 0;
    await tester.pumpWidget(MaterialApp(home: OnboardingScreen(onFinished: () => finished++)));
    await tester.tap(find.text('Skip'));
    await tester.pump();
    expect(finished, 1);
  });
}
