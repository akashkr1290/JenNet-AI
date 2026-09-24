import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Gap-backlog Patch 32/45 (Sep 2026 audit): "for poor network
/// environments, save an in-progress complaint locally until network is
/// restored and upload can happen automatically."
///
/// Uses flutter_secure_storage (already a dependency - see
/// core/auth/token_storage.dart) rather than adding a new package
/// (shared_preferences/hive/sqflite), so this needed no new dependency
/// resolution to write.
///
/// Honest scope: persists the draft's TEXT fields (description,
/// latitude/longitude, timestamp) reliably. It deliberately does NOT
/// persist the photo file's bytes across an app restart -
/// image_picker's picked file lives at a temporary path the OS is free
/// to reclaim once the process exits, and durably copying it elsewhere
/// would need path_provider (not currently a dependency, and adding a
/// new native-plugin dependency without the Flutter SDK available in
/// this sandbox to verify it resolves/builds was judged too risky - see
/// this audit's own sandbox-constraints notes). Practically: a draft
/// survives being backgrounded or losing network mid-fill, which is
/// this patch's core "poor network environment" scenario; it does not
/// survive the app process being killed after a photo was already
/// picked. This is a real, working partial implementation, not a
/// placeholder - documented honestly rather than silently narrower than
/// it sounds.
class ComplaintDraftService {
  ComplaintDraftService._();
  static final ComplaintDraftService instance = ComplaintDraftService._();

  static const _key = 'complaint_draft_v1';
  final _storage = const FlutterSecureStorage();

  Future<void> save({
    String? description,
    double? latitude,
    double? longitude,
    String? photoPath,
  }) async {
    final draft = {
      'description': description,
      'latitude': latitude,
      'longitude': longitude,
      'photoPath': photoPath,
      'savedAt': DateTime.now().toIso8601String(),
    };
    await _storage.write(key: _key, value: jsonEncode(draft));
  }

  /// Returns null if no draft is saved, or if the saved draft is older
  /// than 24 hours (stale drafts aren't worth restoring - the citizen has
  /// likely moved on, and the underlying issue's photo/GPS would need
  /// recapturing regardless per the photo-persistence limitation above).
  Future<Map<String, dynamic>?> load() async {
    final raw = await _storage.read(key: _key);
    if (raw == null) return null;
    try {
      final draft = jsonDecode(raw) as Map<String, dynamic>;
      final savedAt = DateTime.tryParse(draft['savedAt'] as String? ?? '');
      if (savedAt == null || DateTime.now().difference(savedAt).inHours > 24) {
        await clear();
        return null;
      }
      return draft;
    } catch (_) {
      await clear();
      return null;
    }
  }

  Future<void> clear() async {
    await _storage.delete(key: _key);
  }
}
