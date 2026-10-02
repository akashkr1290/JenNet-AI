import 'dart:typed_data';

import 'photo_downscale_stub.dart' if (dart.library.js_interop) 'photo_downscale_web.dart' as impl;

/// Flutter Web only: shrinks a photo picked at full size (so its EXIF could be
/// read first) to at most [maxSide] px as a JPEG - the same size the picker's
/// own resize produced before (audit GAP-009: 12 MP photos made AI analysis
/// time out). Returns null when not on the web or when the browser cannot
/// decode the image; the caller then keeps the original.
Future<Uint8List?> downscaleForUpload(Uint8List bytes, {int maxSide = 1600, double quality = 0.85}) =>
    impl.downscale(bytes, maxSide, quality);
