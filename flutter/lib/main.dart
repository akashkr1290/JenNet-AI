import 'package:flutter/material.dart';

import 'core/app_preferences.dart';
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
        title: 'JANNet AI',
        debugShowCheckedModeBanner: false,
        theme: highContrast
            ? ThemeData(colorScheme: const ColorScheme.highContrastLight(), useMaterial3: true)
            : ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
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
          return const Scaffold(body: Center(child: CircularProgressIndicator()));
        }
        if (!(snapshot.data ?? false)) {
          return const LoginScreen();
        }
        return FutureBuilder<Widget>(
          future: resolveHomeScreen(),
          builder: (context, homeSnapshot) {
            if (homeSnapshot.connectionState != ConnectionState.done) {
              return const Scaffold(body: Center(child: CircularProgressIndicator()));
            }
            return homeSnapshot.data ?? const LoginScreen();
          },
        );
      },
    );
  }
}
