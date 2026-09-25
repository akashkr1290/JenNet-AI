import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Remembers, on this device, that the first-launch onboarding was completed
/// (Get Started or Skip). Uses flutter_secure_storage, which the app already
/// depends on and which works on Android and Flutter Web (browser storage),
/// so no new package is needed. Stored under its own key, so signing out
/// (TokenStorage.clear) never brings the onboarding back.
class OnboardingStore {
  OnboardingStore._();
  static final OnboardingStore instance = OnboardingStore._();

  static const _key = 'jannet_onboarding_done_v1';
  final _storage = const FlutterSecureStorage();

  /// True when onboarding should be skipped. If storage cannot be read, the
  /// onboarding is skipped rather than risking a block in front of sign-in.
  Future<bool> get isCompleted async {
    try {
      return (await _storage.read(key: _key)) == 'true';
    } catch (_) {
      return true;
    }
  }

  Future<void> markCompleted() async {
    try {
      await _storage.write(key: _key, value: 'true');
    } catch (_) {
      // Best effort: at worst onboarding is shown again on the next launch.
    }
  }
}
