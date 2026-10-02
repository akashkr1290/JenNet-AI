import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:image_picker/image_picker.dart';

import '../../../core/api/upload_file.dart';
import '../picked_photo.dart';
import 'device_position.dart';
import 'incident_location.dart';
import 'location_policy.dart';
import 'photo_downscale.dart';
import 'photo_exif.dart';

/// A complaint photo plus the location the app found for it automatically.
class PhotoIntake {
  final PickedPhoto photo;

  /// Proposal only - not yet confirmed by the citizen.
  final IncidentLocation location;

  /// Plain-language note when no automatic location was found.
  final String? note;

  const PhotoIntake({required this.photo, required this.location, this.note});
}

/// Takes or picks a complaint photo and works out where it was taken.
///
/// Source priority (V33): capture GPS (in-app camera, read right after the
/// shot) -> the photo's EXIF GPS -> nothing (the citizen places the pin).
/// The citizen's position is read ONLY for a fresh in-app camera capture -
/// never for a gallery photo, which may have been taken anywhere.
///
/// Android: the picker resizes to 1600 px and keeps the EXIF tags (GPS,
/// DateTimeOriginal) in the resized copy. Web: the browser's resize drops all
/// metadata, so the ORIGINAL is picked, read, and then downscaled here.
class PhotoIntakeService {
  PhotoIntakeService._();

  static Future<PhotoIntake?> takeOrPick(ImageSource source, {DateTime Function() clock = DateTime.now}) async {
    final picker = ImagePicker();
    // Audit GAP-009: 1600 px on the longer side keeps ample detail (the AI
    // model works at 640 px) and stays above the 480p minimum (SRS 17.2).
    final file = kIsWeb
        ? await picker.pickImage(source: source)
        : await picker.pickImage(source: source, imageQuality: 85, maxWidth: 1600, maxHeight: 1600);
    if (file == null) return null;
    final returnedAt = clock();
    final original = await file.readAsBytes();
    final exif = readPhotoExif(original);

    var fresh = source == ImageSource.camera;
    if (fresh && kIsWeb) {
      // A browser "camera" button can also hand back an existing file (desktop
      // file chooser, iOS photo library). Only a just-created photo counts.
      DateTime? created = exif.takenAt;
      if (created == null) {
        try {
          created = await file.lastModified();
        } catch (_) {
          created = null;
        }
      }
      fresh = isFreshCapture(created, returnedAt);
    }

    PositionResult? fix;
    if (fresh) fix = await DevicePosition.read();

    var upload = UploadFile(
      bytes: original,
      filename: file.name.trim().isEmpty ? 'photo.jpg' : file.name,
      contentType: UploadFile.imageContentType(original, declared: file.mimeType, filename: file.name),
    );
    if (kIsWeb) {
      final smaller = await downscaleForUpload(original);
      if (smaller != null && smaller.isNotEmpty && smaller.length < original.length) {
        upload = UploadFile(bytes: smaller, filename: _jpegName(file.name), contentType: 'image/jpeg');
      }
    }
    final photo = PickedPhoto(upload: upload, path: kIsWeb ? null : file.path);
    final decided = decideProposal(
      fresh: fresh,
      fix: fix,
      exif: exif,
      returnedAt: returnedAt,
    );
    return PhotoIntake(photo: photo, location: decided.location, note: decided.note);
  }

  static String _jpegName(String name) {
    final base = name.trim().isEmpty ? 'photo' : name.replaceFirst(RegExp(r'\.[A-Za-z0-9]+$'), '');
    return '$base.jpg';
  }
}

/// True when the photo was created within [LocationPolicy.freshCaptureWindow]
/// of the camera returning it.
bool isFreshCapture(DateTime? created, DateTime returnedAt) =>
    created != null && returnedAt.difference(created).abs() <= LocationPolicy.freshCaptureWindow;

/// The automatic proposal for a photo - pure, unit-tested.
({IncidentLocation location, String? note}) decideProposal({
  required bool fresh,
  required PositionResult? fix,
  required PhotoExif exif,
  required DateTime returnedAt,
}) {
  if (fresh && fix != null && fix.ok) {
    return (
      location: IncidentLocation(
        photoTakenAt: returnedAt,
        detected: DetectedLocation(
          point: fix.point!,
          source: DetectedSource.captureGps,
          accuracyMeters: fix.accuracyMeters,
        ),
      ),
      note: null,
    );
  }
  final takenAt = fresh ? returnedAt : exif.takenAt;
  if (exif.hasGps) {
    return (
      location: IncidentLocation(
        photoTakenAt: takenAt,
        detected: DetectedLocation(point: GeoPoint(exif.latitude!, exif.longitude!), source: DetectedSource.exif),
      ),
      note: null,
    );
  }
  final String note;
  if (fresh && fix?.failure != null) {
    note = '${DevicePosition.explain(fix!.failure!)} Please show us where the problem is on the map.';
  } else {
    note = 'This photo has no location saved in it. Please show us where the problem is on the map.';
  }
  return (location: IncidentLocation(photoTakenAt: takenAt), note: note);
}
