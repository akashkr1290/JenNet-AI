import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:image_picker/image_picker.dart';

import '../../core/api/upload_file.dart';

/// A photo chosen for a complaint (or an officer's after-photo), held in a
/// platform-safe way:
///  * [upload] contains the image BYTES plus its real content type. The
///    multipart upload and the on-screen preview (`Image.memory`) both use
///    it, so the same code works on Android and in the browser.
///  * [path] is the picker's file path on Android (used only to keep the
///    offline draft / upload queue working across restarts). It is null on
///    Flutter Web, where the picker returns a temporary blob URL that is
///    not a durable file path.
class PickedPhoto {
  final UploadFile upload;
  final String? path;

  const PickedPhoto({required this.upload, this.path});

  /// Reads a file returned by image_picker (camera or gallery on Android,
  /// the browser file chooser / mobile-browser camera on Web).
  static Future<PickedPhoto> fromXFile(XFile file) async {
    final bytes = await file.readAsBytes();
    final type = UploadFile.imageContentType(bytes, declared: file.mimeType, filename: file.name);
    final name = file.name.trim().isEmpty ? UploadFile.defaultImageName(type) : file.name;
    return PickedPhoto(
      upload: UploadFile(bytes: bytes, filename: name, contentType: type),
      path: kIsWeb ? null : file.path,
    );
  }

  /// Re-opens a photo saved by path on Android (draft restore / offline
  /// queue). Returns null on Web or if the file is gone or unreadable.
  static Future<PickedPhoto?> fromSavedPath(String? path) async {
    if (kIsWeb || path == null || path.isEmpty) return null;
    try {
      return await fromXFile(XFile(path));
    } catch (_) {
      return null;
    }
  }

  /// User-facing reason this photo cannot be uploaded, or null if it is OK.
  /// Same rules the backend enforces (ComplaintService.validatePhoto).
  String? get validationError {
    if (upload.bytes.isEmpty) return 'The selected photo is empty. Please choose another one.';
    if (!upload.isAllowedImage) return 'Photo must be JPEG, PNG, or WEBP. Please choose a different photo.';
    if (!upload.isWithinSizeLimit) return 'Photo must be under 10 MB. Please choose a smaller photo.';
    return null;
  }
}
