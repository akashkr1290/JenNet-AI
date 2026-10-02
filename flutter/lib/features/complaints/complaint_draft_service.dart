import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/foundation.dart' show visibleForTesting;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../core/api/upload_file.dart';
import 'location/incident_location.dart';
import 'location/location_policy.dart';
import 'picked_photo.dart';

/// A complaint the citizen has started but not submitted.
class ComplaintDraft {
  final String? description;

  /// Where the problem is: the photo's capture/EXIF location and, once
  /// confirmed, the map point - kept with the photo so a report finished
  /// later (from home, say) still uses the place the photo was taken.
  final IncidentLocation location;

  /// The citizen ticked "The problem is still there" for a very old photo.
  final bool stillThere;

  /// Saved with "Save & Report Later" (kept for [LocationPolicy.savedDraftLifetime]);
  /// otherwise an automatic save, kept for 24 hours.
  final bool savedForLater;
  final DateTime savedAt;
  final PickedPhoto? photo;

  const ComplaintDraft({
    this.description,
    this.location = IncidentLocation.none,
    this.stillThere = false,
    this.savedForLater = false,
    required this.savedAt,
    this.photo,
  });

  bool get isEmpty => (description == null || description!.trim().isEmpty) && photo == null;
}

/// Gap-backlog Patch 32/45 (Sep 2026 audit): "for poor network
/// environments, save an in-progress complaint locally until network is
/// restored and upload can happen automatically."
///
/// V33 ("Save & Report Later"): the draft now keeps the PHOTO ITSELF (bytes,
/// on Android and Flutter Web) together with its incident location and photo
/// time, so a complaint photographed at the problem can be finished later
/// from anywhere without losing where it was taken. The app does not rely on
/// the gallery or on file paths the OS may clear.
///
/// Storage: flutter_secure_storage (encrypted; on the web an encrypted
/// localStorage entry), two keys - the small text part is re-saved as the
/// citizen types, the photo only when it changes. Only one draft is kept.
class ComplaintDraftService {
  ComplaintDraftService._();
  static final ComplaintDraftService instance = ComplaintDraftService._();

  static const _key = 'complaint_draft_v2';
  static const _photoKey = 'complaint_draft_photo_v2';
  static const _legacyKey = 'complaint_draft_v1';

  /// Larger photos are not stored (browser storage is limited to a few MB);
  /// the app's photos are about 0.3-1 MB after resizing.
  static const maxStoredPhotoBytes = 3 * 1024 * 1024;
  static const _autoSaveLifetime = Duration(hours: 24);

  final _storage = const FlutterSecureStorage();

  Future<void> save({
    String? description,
    IncidentLocation location = IncidentLocation.none,
    bool stillThere = false,
    bool savedForLater = false,
  }) async {
    try {
      await _storage.write(
        key: _key,
        value: encodeDraft(
          description: description,
          location: location,
          stillThere: stillThere,
          savedForLater: savedForLater,
          savedAt: DateTime.now(),
        ),
      );
    } catch (_) {
      // Draft saving is best-effort; the form itself is unaffected.
    }
  }

  /// Stores (or, for null, removes) the draft photo. Returns false when it
  /// could not be kept on this device.
  Future<bool> savePhoto(PickedPhoto? photo) async {
    try {
      if (photo == null) {
        await _storage.delete(key: _photoKey);
        return true;
      }
      if (photo.upload.bytes.length > maxStoredPhotoBytes) return false;
      await _storage.write(
        key: _photoKey,
        value: jsonEncode({
          'name': photo.upload.filename,
          'type': photo.upload.contentType,
          'bytes': base64Encode(photo.upload.bytes),
          'path': photo.path,
        }),
      );
      return true;
    } catch (_) {
      return false;
    }
  }

  /// The saved draft, or null when there is none or it has expired.
  Future<ComplaintDraft?> load() async {
    try {
      final raw = await _storage.read(key: _key);
      if (raw == null) return await _loadLegacy();
      final draft = decodeDraft(raw, now: DateTime.now());
      if (draft == null) {
        await clear();
        return null;
      }
      final photo = _decodePhoto(await _storage.read(key: _photoKey));
      return ComplaintDraft(
        description: draft.description,
        location: draft.location,
        stillThere: draft.stillThere,
        savedForLater: draft.savedForLater,
        savedAt: draft.savedAt,
        photo: photo,
      );
    } catch (_) {
      await clear();
      return null;
    }
  }

  /// Drafts written before V33 held only the description (their
  /// latitude/longitude were GPS from when the screen opened - not the
  /// incident location - so they are dropped) and an Android photo path.
  Future<ComplaintDraft?> _loadLegacy() async {
    final raw = await _storage.read(key: _legacyKey);
    if (raw == null) return null;
    await _storage.delete(key: _legacyKey);
    final json = jsonDecode(raw) as Map<String, dynamic>;
    final savedAt = DateTime.tryParse(json['savedAt'] as String? ?? '');
    if (savedAt == null || DateTime.now().difference(savedAt) > _autoSaveLifetime) return null;
    final photo = await PickedPhoto.fromSavedPath(json['photoPath'] as String?);
    return ComplaintDraft(description: json['description'] as String?, savedAt: savedAt, photo: photo);
  }

  static PickedPhoto? _decodePhoto(String? raw) {
    if (raw == null) return null;
    try {
      final json = jsonDecode(raw) as Map<String, dynamic>;
      final bytes = base64Decode(json['bytes'] as String);
      return PickedPhoto(
        upload: UploadFile(
          bytes: Uint8List.fromList(bytes),
          filename: json['name'] as String? ?? 'photo.jpg',
          contentType: json['type'] as String? ?? 'image/jpeg',
        ),
        path: json['path'] as String?,
      );
    } catch (_) {
      return null;
    }
  }

  Future<void> clear() async {
    try {
      await _storage.delete(key: _key);
      await _storage.delete(key: _photoKey);
      await _storage.delete(key: _legacyKey);
    } catch (_) {}
  }

  @visibleForTesting
  static String encodeDraft({
    String? description,
    required IncidentLocation location,
    required bool stillThere,
    required bool savedForLater,
    required DateTime savedAt,
  }) =>
      jsonEncode({
        'description': description,
        'location': location.toJson(),
        'stillThere': stillThere,
        'savedForLater': savedForLater,
        'savedAt': savedAt.toUtc().toIso8601String(),
      });

  /// Null when unreadable or expired (24 h, or 30 days for "Save & Report Later").
  @visibleForTesting
  static ComplaintDraft? decodeDraft(String raw, {required DateTime now}) {
    try {
      final json = jsonDecode(raw) as Map<String, dynamic>;
      final savedAt = DateTime.tryParse(json['savedAt'] as String? ?? '')?.toLocal();
      if (savedAt == null) return null;
      final savedForLater = json['savedForLater'] == true;
      final lifetime = savedForLater ? LocationPolicy.savedDraftLifetime : _autoSaveLifetime;
      if (now.difference(savedAt) > lifetime) return null;
      return ComplaintDraft(
        description: json['description'] as String?,
        location: IncidentLocation.fromJson(json['location']),
        stillThere: json['stillThere'] == true,
        savedForLater: savedForLater,
        savedAt: savedAt,
      );
    } catch (_) {
      return null;
    }
  }
}
