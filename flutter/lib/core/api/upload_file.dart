import 'dart:typed_data';

/// A file to send in a multipart request, held as BYTES rather than a
/// `dart:io` path so the same upload code runs on Android and Flutter Web
/// (browsers have no file-system path to read from).
///
/// [contentType] is sent as the part's `Content-Type`. The backend accepts a
/// complaint photo only when that header is `image/jpeg`, `image/png` or
/// `image/webp` (ComplaintService.validatePhoto). `http`'s
/// `MultipartFile.fromPath` defaults to `application/octet-stream`, so
/// uploads built that way were rejected with "Photo must be JPEG, PNG, or
/// WEBP". This class sets the real type instead.
class UploadFile {
  final Uint8List bytes;
  final String filename;
  final String contentType;

  const UploadFile({required this.bytes, required this.filename, required this.contentType});

  /// Mirrors ComplaintService.ALLOWED_CONTENT_TYPES / MAX_PHOTO_BYTES.
  static const allowedImageTypes = {'image/jpeg', 'image/png', 'image/webp'};
  static const maxImageBytes = 10 * 1024 * 1024;

  bool get isAllowedImage => allowedImageTypes.contains(contentType);
  bool get isWithinSizeLimit => bytes.length <= maxImageBytes;

  /// Detects JPEG / PNG / WEBP from the file's leading bytes (its real
  /// format, whatever the name or picker says). Returns null for anything
  /// else.
  static String? sniffImageType(Uint8List b) {
    if (b.length >= 3 && b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF) return 'image/jpeg';
    if (b.length >= 8 &&
        b[0] == 0x89 &&
        b[1] == 0x50 &&
        b[2] == 0x4E &&
        b[3] == 0x47 &&
        b[4] == 0x0D &&
        b[5] == 0x0A &&
        b[6] == 0x1A &&
        b[7] == 0x0A) {
      return 'image/png';
    }
    if (b.length >= 12 &&
        b[0] == 0x52 && // R
        b[1] == 0x49 && // I
        b[2] == 0x46 && // F
        b[3] == 0x46 && // F
        b[8] == 0x57 && // W
        b[9] == 0x45 && // E
        b[10] == 0x42 && // B
        b[11] == 0x50) {
      // P
      return 'image/webp';
    }
    return null;
  }

  /// Best content type for an image: the sniffed format first, then the
  /// type the picker declared, then the file extension. Unknown formats
  /// (HEIC, GIF, ...) become `application/octet-stream`, which callers
  /// reject with [isAllowedImage] before uploading.
  static String imageContentType(Uint8List bytes, {String? declared, String? filename}) {
    final sniffed = sniffImageType(bytes);
    if (sniffed != null) return sniffed;
    final d = declared?.toLowerCase().trim();
    if (d == 'image/jpg' || d == 'image/pjpeg') return 'image/jpeg';
    if (d != null && allowedImageTypes.contains(d)) return d;
    final name = filename?.toLowerCase() ?? '';
    if (name.endsWith('.jpg') || name.endsWith('.jpeg')) return 'image/jpeg';
    if (name.endsWith('.png')) return 'image/png';
    if (name.endsWith('.webp')) return 'image/webp';
    return 'application/octet-stream';
  }

  static String defaultImageName(String contentType) => switch (contentType) {
        'image/png' => 'photo.png',
        'image/webp' => 'photo.webp',
        _ => 'photo.jpg',
      };
}
