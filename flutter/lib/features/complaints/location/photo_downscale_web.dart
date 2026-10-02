import 'dart:async';
import 'dart:js_interop';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:web/web.dart' as web;

/// Browser canvas re-encode. Drawing an <img> applies the EXIF orientation,
/// and the result carries no metadata at all (the location was already read).
Future<Uint8List?> downscale(Uint8List bytes, int maxSide, double quality) async {
  final url = web.URL.createObjectURL(web.Blob(<JSAny>[bytes.toJS].toJS));
  try {
    final image = web.HTMLImageElement();
    image.src = url;
    await image.decode().toDart.timeout(const Duration(seconds: 20));
    final width = image.naturalWidth;
    final height = image.naturalHeight;
    if (width <= 0 || height <= 0) return null;
    final scale = math.min(1.0, maxSide / math.max(width, height));
    final canvas = web.HTMLCanvasElement()
      ..width = math.max(1, (width * scale).round())
      ..height = math.max(1, (height * scale).round());
    final context = canvas.getContext('2d') as web.CanvasRenderingContext2D?;
    if (context == null) return null;
    context.drawImage(image, 0, 0, canvas.width, canvas.height);
    final done = Completer<web.Blob?>();
    canvas.toBlob(
      ((web.Blob? blob) {
        done.complete(blob);
      }).toJS,
      'image/jpeg',
      quality.toJS,
    );
    final blob = await done.future.timeout(const Duration(seconds: 20));
    if (blob == null) return null;
    final buffer = await blob.arrayBuffer().toDart;
    return buffer.toDart.asUint8List();
  } catch (_) {
    return null;
  } finally {
    web.URL.revokeObjectURL(url);
  }
}
