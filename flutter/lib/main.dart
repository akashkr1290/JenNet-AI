import 'package:flutter/material.dart';

import 'core/app_preferences.dart';
import 'core/theme/jan_theme.dart';
import 'core/theme/jan_tokens.dart';
import 'core/widgets/jan_logo.dart';
import 'core/widgets/jan_surfaces.dart';
import 'features/auth/auth_api.dart';
import 'features/auth/screens/login_screen.dart';
import 'features/home_router.dart';

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
/// (Phase 6-minimal) login screen. No token-expiry check here - ApiClient's
/// refresh-on-401 handles an expired access token transparently on the
/// first real API call; a fully expired refresh token still routes back
/// to LoginScreen at that point via AuthApi's logout-on-refresh-failure
/// path in ApiClient._tryRefresh.
class _StartupGate extends StatelessWidget {
  const _StartupGate();

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<bool>(
      future: AuthApi.instance.isLoggedIn,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const _SplashScreen();
        }
        if (!(snapshot.data ?? false)) {
          return const LoginScreen();
        }
        return FutureBuilder<Widget>(
          future: resolveHomeScreen(),
          builder: (context, homeSnapshot) {
            if (homeSnapshot.connectionState != ConnectionState.done) {
              return const _SplashScreen();
            }
            return homeSnapshot.data ?? const LoginScreen();
          },
        );
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
