import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/core/api/upload_file.dart';
import 'package:jannet_ai/features/complaints/picked_photo.dart';

/// Post-UI gap fix: the complaint photo must reach the backend with a real
/// image content type (ComplaintService.validatePhoto accepts only
/// image/jpeg, image/png, image/webp). NOT EXECUTED in the workspace that
/// wrote it (no Flutter SDK there) - run with `flutter test`.
void main() {
  final jpeg = Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10]);
  final png = Uint8List.fromList([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00]);
  final webp = Uint8List.fromList('RIFF\x00\x00\x00\x00WEBPVP8 '.codeUnits);
  final gif = Uint8List.fromList('GIF89a'.codeUnits);

  test('sniffs JPEG, PNG and WEBP from the leading bytes', () {
    expect(UploadFile.sniffImageType(jpeg), 'image/jpeg');
    expect(UploadFile.sniffImageType(png), 'image/png');
    expect(UploadFile.sniffImageType(webp), 'image/webp');
    expect(UploadFile.sniffImageType(gif), isNull);
  });

  test('real bytes win over a wrong name or declared type', () {
    expect(UploadFile.imageContentType(png, declared: 'image/jpeg', filename: 'x.jpg'), 'image/png');
  });

  test('falls back to the declared type, then the extension', () {
    final unknown = Uint8List.fromList([1, 2, 3]);
    expect(UploadFile.imageContentType(unknown, declared: 'image/jpg'), 'image/jpeg');
    expect(UploadFile.imageContentType(unknown, filename: 'photo.WEBP'), 'image/webp');
    expect(UploadFile.imageContentType(unknown, filename: 'photo.heic'), 'application/octet-stream');
  });

  test('PickedPhoto rejects unsupported, empty and oversized photos with a clear message', () {
    PickedPhoto photo(Uint8List bytes, String type) =>
        PickedPhoto(upload: UploadFile(bytes: bytes, filename: 'p', contentType: type));

    expect(photo(jpeg, 'image/jpeg').validationError, isNull);
    expect(photo(gif, 'application/octet-stream').validationError, contains('JPEG, PNG, or WEBP'));
    expect(photo(Uint8List(0), 'image/jpeg').validationError, contains('empty'));
    expect(photo(Uint8List(UploadFile.maxImageBytes + 1), 'image/jpeg').validationError, contains('10 MB'));
  });
}
