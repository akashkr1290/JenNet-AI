import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_secure_storage/test/test_flutter_secure_storage_platform.dart';
import 'package:flutter_secure_storage_platform_interface/flutter_secure_storage_platform_interface.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/core/platform_status.dart';
import 'package:jannet_ai/core/widgets/error_text.dart';
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
/// The plugin's test storage, but reads take a moment like on a real device -
/// so the Sign In spinner is actually on screen before the request fails.
class _SlowTestStorage extends TestFlutterSecureStoragePlatform {
  _SlowTestStorage() : super({});

  @override
  Future<String?> read({required String key, required Map<String, String> options}) async {
    await Future<void>.delayed(const Duration(milliseconds: 300));
    return super.read(key: key, options: options);
  }
}

void main() {
  Widget wrap() => const MaterialApp(home: LoginScreen());

  // The sign-in layout's status banner starts a 5-minute poll the first time it
  // is shown; stop it at the end of every test so no timer outlives the test.
  void stopStatusPoll() => PlatformStatusService.instance.stop();

  // Every API call first reads the saved login token from secure storage, a
  // platform plugin that does not exist in widget tests. The plugin's own test
  // mode makes that read return "no token" at once, so the two tests below that
  // really call the API (ward list, login) are deterministic.
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));

  testWidgets('renders identifier field, password field, and Sign In button', (tester) async {
    await tester.pumpWidget(wrap());

    expect(find.widgetWithText(TextField, 'Mobile number or email'), findsOneWidget);
    expect(find.byType(TextField), findsNWidgets(2)); // identifier + password
    expect(find.text('Sign In'), findsOneWidget);
    expect(find.text("Don't have an account? Register"), findsOneWidget);
    expect(find.text('Forgot Password?'), findsOneWidget);
    stopStatusPoll();
  });

  testWidgets('the password field obscures input', (tester) async {
    await tester.pumpWidget(wrap());

    final passwordField = tester.widgetList<TextField>(find.byType(TextField)).last;
    expect(passwordField.obscureText, isTrue);
    stopStatusPoll();
  });

  testWidgets('tapping "Forgot Password?" navigates to ForgotPasswordScreen', (tester) async {
    await tester.pumpWidget(wrap());

    await tester.ensureVisible(find.text('Forgot Password?'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Forgot Password?'));
    await tester.pumpAndSettle();

    expect(find.byType(ForgotPasswordScreen), findsOneWidget);
    stopStatusPoll();
  });

  testWidgets('tapping "Register" navigates to RegisterScreen', (tester) async {
    await tester.pumpWidget(wrap());

    // The link sits below the fold on the 800x600 test surface (the test
    // font renders every glyph a full em wide, so text wraps more than on a
    // device) - scroll it into view first so the tap actually lands.
    await tester.ensureVisible(find.text("Don't have an account? Register"));
    await tester.pumpAndSettle();
    await tester.tap(find.text("Don't have an account? Register"));
    // RegisterScreen loads the ward list on open (fails here - no server);
    // fixed pumps instead of pumpAndSettle so a spinner can never hang the test.
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 500));
    }

    expect(find.byType(RegisterScreen), findsOneWidget);
    stopStatusPoll();
  });

  testWidgets('tapping Sign In shows a loading spinner then a graceful error '
      'on network failure, rather than crashing', (tester) async {
    // With the instant test storage the request fails before the first frame,
    // so the spinner would never be visible - use the slower one here.
    FlutterSecureStoragePlatform.instance = _SlowTestStorage();
    await tester.pumpWidget(wrap());
    await tester.enterText(find.byType(TextField).first, '+911234567890');
    await tester.enterText(find.byType(TextField).last, 'somePassword1!');

    await tester.ensureVisible(find.text('Sign In'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sign In'));
    await tester.pump(); // start the async _submit, enter loading state

    expect(find.byType(CircularProgressIndicator), findsOneWidget);

    // Let the (failing, no-server) HTTP call reject and the catch block run.
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 500));
    }

    expect(find.byType(CircularProgressIndicator), findsNothing);
    // Which message appears depends on how the request fails in the test
    // environment ("Login failed: ...", "Something went wrong", "Could not reach
    // the server ...") - what matters is that an error is shown, not a crash.
    expect(find.byType(ErrorText), findsOneWidget);
    // The button must be re-enabled (not permanently stuck loading) so the
    // user can retry.
    final button = tester.widget<FilledButton>(find.byType(FilledButton));
    expect(button.onPressed, isNotNull);
    stopStatusPoll();
  });
}
