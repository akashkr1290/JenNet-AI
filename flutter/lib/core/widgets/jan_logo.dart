import 'package:flutter/material.dart';

import '../theme/jan_tokens.dart';

/// JanNet AI brand mark: a navy "J" whose stem connects into a small network
/// of civic nodes (the reference logo's motif), drawn as vectors so it is
/// crisp at every size on Android and Web and needs no image asset.
class JanLogoMark extends StatelessWidget {
  final double size;

  /// White-on-navy variant for dark surfaces (e.g. the web side rail).
  final bool onDark;

  const JanLogoMark({super.key, this.size = 36, this.onDark = false});

  @override
  Widget build(BuildContext context) {
    return SizedBox.square(
      dimension: size,
      child: CustomPaint(painter: _LogoMarkPainter(onDark: onDark)),
    );
  }
}

class _LogoMarkPainter extends CustomPainter {
  final bool onDark;
  _LogoMarkPainter({required this.onDark});

  @override
  void paint(Canvas canvas, Size size) {
    final s = size.shortestSide;
    final stemColor = onDark ? JanColors.white : JanColors.navy;
    final lineColor = (onDark ? const Color(0xFF9CC5EC) : JanColors.sky).withValues(alpha: 0.85);

    final hub = Offset(0.64 * s, 0.16 * s);
    final nodes = <Offset, double>{
      Offset(0.34 * s, 0.30 * s): 0.065 * s,
      Offset(0.14 * s, 0.17 * s): 0.055 * s,
      Offset(0.20 * s, 0.47 * s): 0.050 * s,
    };

    final link = Paint()
      ..color = lineColor
      ..strokeWidth = 0.035 * s
      ..strokeCap = StrokeCap.round;
    final nodeList = nodes.keys.toList();
    canvas.drawLine(hub, nodeList[0], link);
    canvas.drawLine(nodeList[0], nodeList[1], link);
    canvas.drawLine(nodeList[0], nodeList[2], link);

    // The "J": stem + hooked base.
    final stem = Paint()
      ..color = stemColor
      ..style = PaintingStyle.stroke
      ..strokeWidth = 0.17 * s
      ..strokeCap = StrokeCap.round;
    final path = Path()
      ..moveTo(0.64 * s, 0.32 * s)
      ..lineTo(0.64 * s, 0.66 * s)
      ..arcToPoint(Offset(0.30 * s, 0.66 * s), radius: Radius.circular(0.17 * s), clockwise: true);
    canvas.drawPath(path, stem);

    canvas.drawCircle(hub, 0.085 * s, Paint()..color = JanColors.sky);
    var i = 0;
    for (final entry in nodes.entries) {
      final color = i == 2 ? JanColors.tealBrand : JanColors.sky;
      canvas.drawCircle(entry.key, entry.value, Paint()..color = color);
      i++;
    }
  }

  @override
  bool shouldRepaint(covariant _LogoMarkPainter oldDelegate) => oldDelegate.onDark != onDark;
}

/// Mark + "JanNet AI" wordmark. Announced once to screen readers as "JanNet AI".
class JanLogo extends StatelessWidget {
  final double markSize;
  final double fontSize;
  final bool onDark;
  final bool showMark;

  const JanLogo({super.key, this.markSize = 34, this.fontSize = 22, this.onDark = false, this.showMark = true});

  @override
  Widget build(BuildContext context) {
    final nameColor = onDark ? JanColors.white : JanColors.navy;
    return Semantics(
      label: 'JanNet AI',
      excludeSemantics: true,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (showMark) ...[
            JanLogoMark(size: markSize, onDark: onDark),
            const SizedBox(width: 8),
          ],
          Text.rich(
            TextSpan(
              children: [
                TextSpan(text: 'JanNet ', style: TextStyle(color: nameColor)),
                TextSpan(
                  text: 'AI',
                  style: TextStyle(color: onDark ? const Color(0xFF7FD1CB) : JanColors.teal),
                ),
              ],
            ),
            style: TextStyle(fontSize: fontSize, fontWeight: FontWeight.w800, letterSpacing: -0.3, height: 1.1),
          ),
        ],
      ),
    );
  }
}
