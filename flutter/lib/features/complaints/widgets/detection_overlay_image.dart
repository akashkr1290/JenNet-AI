import 'package:flutter/material.dart';

import '../../../core/theme/jan_tokens.dart';
import '../models/complaint.dart';

/// Gap-backlog Patch 42 (Sep 2026 strict recheck): draws the AI's detection
/// boxes over the photo it classified. Box coordinates arrive normalised to
/// 0..1 of the original photo (see backend AiClassificationResponse), so the
/// photo is laid out at its real aspect ratio (resolved from the image
/// itself) with BoxFit.fill, making relative coordinates line up exactly.
class DetectionOverlayImage extends StatefulWidget {
  final String url;
  final List<DetectedBox> boxes;
  const DetectionOverlayImage({super.key, required this.url, required this.boxes});

  @override
  State<DetectionOverlayImage> createState() => _DetectionOverlayImageState();
}

class _DetectionOverlayImageState extends State<DetectionOverlayImage> {
  ImageStream? _stream;
  late final ImageStreamListener _listener;
  Size? _size;

  @override
  void initState() {
    super.initState();
    _listener = ImageStreamListener((info, _) {
      if (mounted) {
        setState(() => _size = Size(info.image.width.toDouble(), info.image.height.toDouble()));
      }
    }, onError: (_, __) {});
    _stream = NetworkImage(widget.url).resolve(const ImageConfiguration());
    _stream!.addListener(_listener);
  }

  @override
  void dispose() {
    _stream?.removeListener(_listener);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final size = _size;
    if (size == null || size.height == 0) {
      return Image.network(widget.url,
          semanticLabel: 'Complaint photo',
          errorBuilder: (_, __, ___) => const Icon(Icons.broken_image_outlined, size: 48, color: JanColors.muted));
    }
    return Semantics(
      label: widget.boxes.isEmpty
          ? 'Complaint photo'
          : 'Complaint photo with AI detections: ${widget.boxes.map((b) => b.className).join(', ')}',
      child: AspectRatio(
        aspectRatio: size.width / size.height,
        child: Stack(fit: StackFit.expand, children: [
          // Described by the enclosing Semantics label (incl. detections); not announced twice.
          Image.network(widget.url, fit: BoxFit.fill, excludeFromSemantics: true),
          CustomPaint(painter: _BoxPainter(widget.boxes)),
        ]),
      ),
    );
  }
}

class _BoxPainter extends CustomPainter {
  final List<DetectedBox> boxes;
  _BoxPainter(this.boxes);

  @override
  void paint(Canvas canvas, Size size) {
    final stroke = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3
      ..color = JanColors.amber; // brand amber; navy label text on it is 5.7:1
    for (final b in boxes) {
      final rect = Rect.fromLTRB(b.x1 * size.width, b.y1 * size.height, b.x2 * size.width, b.y2 * size.height);
      canvas.drawRect(rect, stroke);
      final tp = TextPainter(
        text: TextSpan(
          text: ' ${b.className.replaceAll('_', ' ')} ${b.confidence.toStringAsFixed(0)}% ',
          style: const TextStyle(
            color: JanColors.navy,
            fontSize: 13,
            fontWeight: FontWeight.w700,
            backgroundColor: JanColors.amber,
          ),
        ),
        textDirection: TextDirection.ltr,
      )..layout();
      final dy = rect.top - tp.height < 0 ? rect.top : rect.top - tp.height;
      tp.paint(canvas, Offset(rect.left, dy));
    }
  }

  @override
  bool shouldRepaint(covariant _BoxPainter old) => old.boxes != boxes;
}
