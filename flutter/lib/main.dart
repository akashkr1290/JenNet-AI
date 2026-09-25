import 'package:flutter/material.dart';

import 'core/app_preferences.dart';
import 'core/theme/jan_theme.dart';
import 'core/theme/jan_tokens.dart';
import 'core/widgets/jan_logo.dart';
import 'core/widgets/jan_surfaces.dart';
import 'features/auth/auth_api.dart';
import 'features/auth/screens/login_screen.dart';
import 'features/home_router.dart';
import 'features/onboarding/onboarding_screen.dart';
import 'features/onboarding/onboarding_store.dart';

void main() {
  runApp(const JannetApp());
}

class JannetApp extends StatelessWidget {
  const JannetApp({super.key});

  @override
  Widget build(BuildContext context) {
    // Gap-backlog Patch 46: the saved high-contrast setting is applied app-wide.
    // Text size follows the OS accessibility setting (no textScaler clamp).
    return ValueListenableBuilder<bool>(
      valueListenable: AppPreferences.highContrast,
      builder: (context, highContrast, _) => MaterialApp(
        title: 'JanNet AI',
        debugShowCheckedModeBanner: false,
        // UI redesign: the JanNet AI design system (lib/core/theme). The
        // high-contrast setting keeps the same components with deeper
        // text, borders and interactive colours.
        theme: JanTheme.light(highContrast: highContrast),
        home: const _StartupGate(),
      ),
    );
  }
}

/// Minimal splash/routing gate - if a token is already stored, skip
/// straight to the role-appropriate home shell (Phase 12: Citizen vs
/// Officer/Department Head, via resolveHomeScreen); otherwise show the
/// login screen. No token-expiry check here - ApiClient's refresh-on-401
/// handles an expired access token transparently on the first real API
/// call; a fully expired refresh token still routes back to LoginScreen at
/// that point via AuthApi's logout-on-refresh-failure path in
/// ApiClient._tryRefresh.
///
/// Post-UI gap fix - first-launch onboarding:
///  * signed in                        -> home (onboarding never shown)
///  * signed out, onboarding not done  -> OnboardingScreen -> LoginScreen
///  * signed out, onboarding done      -> LoginScreen
/// The startup checks now run once (initState) instead of on every rebuild,
/// so a theme change (high contrast) no longer re-runs them.
class _StartupGate extends StatefulWidget {
  const _StartupGate();

  @override
  State<_StartupGate> createState() => _StartupGateState();
}

class _StartupGateState extends State<_StartupGate> {
  late final Future<Widget> _start = _decide();

  Future<Widget> _decide() async {
    if (await AuthApi.instance.isLoggedIn) {
      return resolveHomeScreen();
    }
    if (!await OnboardingStore.instance.isCompleted) {
      return OnboardingScreen(onFinished: _finishOnboarding);
    }
    return const LoginScreen();
  }

  Future<void> _finishOnboarding() async {
    await OnboardingStore.instance.markCompleted();
    if (!mounted) return;
    Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => const LoginScreen()));
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Widget>(
      future: _start,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const _SplashScreen();
        }
        return snapshot.data ?? const LoginScreen();
      },
    );
  }
}

/// UI redesign: reference splash - JanNet AI mark, wordmark and tagline on
/// the city backdrop while the session is checked. Shown only for as long
/// as the startup checks above take.
class _SplashScreen extends StatelessWidget {
  const _SplashScreen();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: JanBackdrop(
        child: Center(
          child: Semantics(
            label: 'JanNet AI is starting',
            liveRegion: true,
            child: const Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                JanLogoMark(size: 96),
                SizedBox(height: JanSpace.lg),
                JanLogo(showMark: false, fontSize: 34),
                SizedBox(height: JanSpace.xs),
                Text(
                  'Report. Track. Improve.',
                  style: TextStyle(color: JanColors.muted, fontSize: 17, letterSpacing: 0.4),
                ),
                SizedBox(height: JanSpace.xxl),
                SizedBox(width: 28, height: 28, child: CircularProgressIndicator(strokeWidth: 3)),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
