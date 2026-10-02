import 'dart:typed_data';

/// Android / tests: the image picker already resized the photo (keeping EXIF).
Future<Uint8List?> downscale(Uint8List bytes, int maxSide, double quality) async => null;
