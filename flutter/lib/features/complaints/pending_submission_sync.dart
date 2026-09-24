import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../core/api/api_exception.dart';
import 'complaint_draft_service.dart';
import 'complaints_api.dart';

/// Gap-backlog Patch 45 (final recheck, Sep 2026): automatic upload of a
/// complaint that could not be sent because the network was unavailable.
///
/// When a submission fails without any server response (no connection,
/// timeout), the complete submission - photo path, description, location,
/// ward, location source, queued time - is stored here. [start] then retries
/// automatically whenever the app returns to the foreground and every 60
/// seconds while it is open. Uses only packages the app already depends on
/// (flutter_secure_storage, dart:io) - no connectivity plugin; a retry is
/// simply attempted and a network failure leaves the item queued.
///
/// If the SERVER rejects the complaint (an ApiException - e.g. validation),
/// automatic retry stops and the citizen is told, so a permanently invalid
/// item can never loop forever. Honest limits: retries only run while the
/// app process is alive (no OS background job), and if the OS has purged
/// the cached photo file the citizen is asked to re-submit.
class PendingSubmissionSync with WidgetsBindingObserver {
  PendingSubmissionSync._();
  static final PendingSubmissionSync instance = PendingSubmissionSync._();

  static const _key = 'pending_submission_v1';
  static const _retryInterval = Duration(seconds: 60);
  final _storage = const FlutterSecureStorage();

  /// Latest user-facing sync outcome; the citizen shell shows it as a SnackBar.
  final ValueNotifier<String?> messages = ValueNotifier<String?>(null);

  Timer? _timer;
  bool _started = false;
  bool _syncing = false;

  Future<void> queue({
    required String photoPath,
    String? description,
    required double latitude,
    required double longitude,
    int? wardId,
    required String locationSource,
  }) async {
    await _storage.write(
      key: _key,
      value: jsonEncode({
        'photoPath': photoPath,
        'description': description,
        'latitude': latitude,
        'longitude': longitude,
        'wardId': wardId,
        'locationSource': locationSource,
        'queuedAt': DateTime.now().toIso8601String(),
      }),
    );
  }

  Future<bool> get hasPending async => (await _storage.read(key: _key)) != null;

  void start() {
    if (_started) return;
    _started = true;
    WidgetsBinding.instance.addObserver(this);
    _timer = Timer.periodic(_retryInterval, (_) => trySync());
    trySync();
  }

  void stop() {
    _timer?.cancel();
    _timer = null;
    if (_started) WidgetsBinding.instance.removeObserver(this);
    _started = false;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) trySync();
  }

  Future<void> trySync() async {
    if (_syncing) return;
    _syncing = true;
    try {
      final raw = await _storage.read(key: _key);
      if (raw == null) return;
      final p = jsonDecode(raw) as Map<String, dynamic>;
      final photo = File(p['photoPath'] as String);
      if (!photo.existsSync()) {
        await _storage.delete(key: _key);
        messages.value = 'A saved complaint could not be uploaded because its photo is no longer '
            'on this device. Please submit it again.';
        return;
      }
      try {
        final complaint = await ComplaintsApi.instance.submit(
          photo: photo,
          description: p['description'] as String?,
          latitude: (p['latitude'] as num).toDouble(),
          longitude: (p['longitude'] as num).toDouble(),
          wardId: (p['wardId'] as num?)?.toInt(),
          locationSource: p['locationSource'] as String? ?? 'DEVICE_GPS',
        );
        await _storage.delete(key: _key);
        await ComplaintDraftService.instance.clear();
        messages.value = 'Your saved complaint was uploaded: ${complaint.referenceNumber}';
      } on ApiException catch (e) {
        // The server answered and refused it - retrying would never succeed.
        await _storage.delete(key: _key);
        messages.value = 'Your saved complaint could not be submitted: ${e.message}';
      } catch (_) {
        // Still offline (no server response) - keep it queued for the next attempt.
      }
    } finally {
      _syncing = false;
    }
  }
}
