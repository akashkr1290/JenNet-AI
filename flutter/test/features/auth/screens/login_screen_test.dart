import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/auth/screens/forgot_password_screen.dart';
import 'package:jannet_ai/features/auth/screens/login_screen.dart';
import 'package:jannet_ai/features/auth/screens/register_screen.dart';

/// Phase 20: widget tests for [LoginScreen]. No HTTP/platform-channel
/// mocking is set up here (ApiClient/TokenStorage are real singletons) -
/// this workspace has no outbound network, so the "Sign In" happy path
/// cannot be exercised without a mock backend, which is out of scope for
/// this phase's Flutter test tooling (no `mockito`/`http_mock_adapter`
/// dev-dependency exists yet - see PROJECT_PROGRESS.md's Phase 20 TESTS
/// section). What IS tested is exactly what's reachable without one: the
/// static widget tree, the two navigation links, and - genuinely valuable
/// as a failure/edge-case test - that a real network failure surfaces a
/// user-visible error message rather than an unhandled exception/crash,
/// which is real behavior this screen's own try/catch provides regardless
/// of mocking.
///
/// NOT EXECUTED in this workspace (no `flutter`/`dart` SDK on PATH - see
/// PROJECT_PROGRESS.md's Phase 20 TESTS section). Manually validated
/// against login_screen.dart's actual widget tree and _submit's try/catch.
void main() {
  Widget wrap() => const MaterialApp(home: LoginScreen());

  testWidgets('renders identifier field, password field, and Sign In button', (tester) async {
    await tester.pumpWidget(wrap());

    expect(find.widgetWithText(TextField, 'Mobile number or email'), findsOneWidget);
    expect(find.byType(TextField), findsNWidgets(2)); // identifier + password
    expect(find.text('Sign In'), findsOneWidget);
    expect(find.text("Don't have an account? Register"), findsOneWidget);
    expect(find.text('Forgot Password?'), findsOneWidget);
  });

  testWidgets('the password field obscures input', (tester) async {
    await tester.pumpWidget(wrap());

    final passwordField = tester.widgetList<TextField>(find.byType(TextField)).last;
    expect(passwordField.obscureText, isTrue);
  });

  testWidgets('tapping "Forgot Password?" navigates to ForgotPasswordScreen', (tester) async {
    await tester.pumpWidget(wrap());

    await tester.ensureVisible(find.text('Forgot Password?'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Forgot Password?'));
    await tester.pumpAndSettle();

    expect(find.byType(ForgotPasswordScreen), findsOneWidget);
  });

  testWidgets('tapping "Register" navigates to RegisterScreen', (tester) async {
    await tester.pumpWidget(wrap());

    // The link sits below the fold on the 800x600 test surface (the test
    // font renders every glyph a full em wide, so text wraps more than on a
    // device) - scroll it into view first so the tap actually lands.
    await tester.ensureVisible(find.text("Don't have an account? Register"));
    await tester.pumpAndSettle();
    await tester.tap(find.text("Don't have an account? Register"));
    await tester.pumpAndSettle();

    expect(find.byType(RegisterScreen), findsOneWidget);
  });

  testWidgets('tapping Sign In shows a loading spinner then a graceful error '
      'on network failure, rather than crashing', (tester) async {
    await tester.pumpWidget(wrap());
    await tester.enterText(find.byType(TextField).first, '+911234567890');
    await tester.enterText(find.byType(TextField).last, 'somePassword1!');

    await tester.ensureVisible(find.text('Sign In'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sign In'));
    await tester.pump(); // start the async _submit, enter loading state

    expect(find.byType(CircularProgressIndicator), findsOneWidget);

    // Let the (failing, no-network) HTTP call resolve/reject and the
    // catch block run.
    await tester.pumpAndSettle();

    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(find.textContaining('Login failed'), findsOneWidget);
    // The button must be re-enabled (not permanently stuck loading) so the
    // user can retry.
    final button = tester.widget<FilledButton>(find.byType(FilledButton));
    expect(button.onPressed, isNotNull);
  });
}
