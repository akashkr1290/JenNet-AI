import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme/jan_tokens.dart';

/// Vector illustrations in the JanNet AI reference style: outline city
/// skylines, a "civic network" of connected nodes, and icon badges. Drawn
/// with CustomPainter so there are no image assets to ship, they scale to
/// any screen, and they render identically on Android and Web.

/// Outline skyline along the bottom edge of its box.
class SkylinePainter extends CustomPainter {
  final Color color;
  final double strokeWidth;
  final bool windows;

  const SkylinePainter({required this.color, this.strokeWidth = 1.2, this.windows = true});

  // Building footprints as fractions of the box: (left, width, height).
  static const List<List<double>> _buildings = [
    [0.00, 0.07, 0.30], [0.06, 0.06, 0.46], [0.13, 0.08, 0.34], [0.20, 0.05, 0.62],
    [0.26, 0.09, 0.40], [0.34, 0.06, 0.74], [0.41, 0.08, 0.50], [0.50, 0.05, 0.86],
    [0.56, 0.08, 0.44], [0.64, 0.06, 0.66], [0.71, 0.09, 0.38], [0.79, 0.06, 0.58],
    [0.86, 0.07, 0.32], [0.93, 0.07, 0.48],
  ];

  @override
  void paint(Canvas canvas, Size size) {
    final stroke = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth;
    final dot = Paint()..color = color;
    for (final b in _buildings) {
      final left = b[0] * size.width;
      final width = b[1] * size.width;
      final height = b[2] * size.height;
      final rect = Rect.fromLTWH(left, size.height - height, width, height);
      canvas.drawRect(rect, stroke);
      if (windows && width > 10) {
        for (var y = rect.top + 8; y < rect.bottom - 6; y += 9) {
          for (var x = rect.left + 5; x < rect.right - 4; x += 7) {
            canvas.drawRect(Rect.fromLTWH(x, y, 2.2, 2.2), dot);
          }
        }
      }
    }
    canvas.drawLine(Offset(0, size.height - 0.5), Offset(size.width, size.height - 0.5), stroke);
  }

  @override
  bool shouldRepaint(covariant SkylinePainter oldDelegate) =>
      oldDelegate.color != color || oldDelegate.strokeWidth != strokeWidth || oldDelegate.windows != windows;
}

/// A network of connected civic nodes spanning an arc across the box.
class CivicNetworkPainter extends CustomPainter {
  final Color lineColor;
  final List<Color> nodeColors;
  final int seed;

  const CivicNetworkPainter({
    this.lineColor = const Color(0x665C9FD8),
    this.nodeColors = const [JanColors.sky, JanColors.tealBrand, JanColors.amber],
    this.seed = 7,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final rnd = math.Random(seed);
    final points = <Offset>[];
    const count = 11;
    for (var i = 0; i < count; i++) {
      final t = i / (count - 1);
      final x = size.width * (0.04 + 0.92 * t);
      final arc = math.sin(t * math.pi);
      final y = size.height * (0.78 - 0.55 * arc) + (rnd.nextDouble() - 0.5) * size.height * 0.12;
      points.add(Offset(x, y));
    }
    final line = Paint()
      ..color = lineColor
      ..strokeWidth = 1.2;
    for (var i = 0; i < points.length; i++) {
      if (i + 1 < points.length) canvas.drawLine(points[i], points[i + 1], line);
      if (i + 3 < points.length && i.isEven) canvas.drawLine(points[i], points[i + 3], line);
    }
    for (var i = 0; i < points.length; i++) {
      final r = 3.0 + (i % 3) * 1.4;
      final color = nodeColors[i % nodeColors.length];
      canvas.drawCircle(points[i], r + 2.5, Paint()..color = color.withValues(alpha: 0.18));
      canvas.drawCircle(points[i], r, Paint()..color = color);
    }
  }

  @override
  bool shouldRepaint(covariant CivicNetworkPainter oldDelegate) =>
      oldDelegate.lineColor != lineColor || oldDelegate.seed != seed;
}

/// Circular icon badge used inside illustrations.
class _IconBubble extends StatelessWidget {
  final IconData icon;
  final Color color;
  final double size;
  const _IconBubble({required this.icon, required this.color, required this.size});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: color,
        shape: BoxShape.circle,
        border: Border.all(color: JanColors.white, width: 2),
        boxShadow: const [BoxShadow(color: Color(0x22203A5F), blurRadius: 8, offset: Offset(0, 3))],
      ),
      child: Icon(icon, color: JanColors.white, size: size * 0.52),
    );
  }
}

/// Hero illustration: skyline + civic network with service icons and a
/// location pin, as in the reference dashboard banner and login header.
/// Decorative only - excluded from the semantics tree.
class JanCivicHero extends StatelessWidget {
  final double height;
  const JanCivicHero({super.key, this.height = 150});

  @override
  Widget build(BuildContext context) {
    return ExcludeSemantics(
      child: SizedBox(
        height: height,
        child: LayoutBuilder(builder: (context, constraints) {
          final w = constraints.maxWidth;
          final h = height;
          final bubble = (h * 0.24).clamp(26.0, 40.0);
          return Stack(
            clipBehavior: Clip.none,
            children: [
              Positioned.fill(
                top: h * 0.35,
                child: const CustomPaint(painter: SkylinePainter(color: Color(0x805C9FD8), windows: true)),
              ),
              Positioned.fill(child: CustomPaint(painter: CivicNetworkPainter(seed: 3))),
              Positioned(
                left: w * 0.18 - bubble / 2,
                top: h * 0.14,
                child: _IconBubble(icon: Icons.park_outlined, color: JanColors.teal, size: bubble),
              ),
              Positioned(
                left: w * 0.5 - bubble / 2,
                top: 0,
                child: _IconBubble(icon: Icons.water_drop_outlined, color: JanColors.primary, size: bubble),
              ),
              Positioned(
                left: w * 0.82 - bubble / 2,
                top: h * 0.14,
                child: _IconBubble(icon: Icons.light_outlined, color: JanColors.teal, size: bubble),
              ),
              Positioned(
                left: w * 0.5 - bubble * 0.6,
                bottom: h * 0.02,
                child: Icon(Icons.location_on, size: bubble * 1.2, color: JanColors.tealBrand),
              ),
            ],
          );
        }),
      ),
    );
  }
}

/// Illustration for empty / error / success states: a tinted disc holding a
/// large icon, orbited by a few connected civic nodes.
class JanStateIllustration extends StatelessWidget {
  final IconData icon;
  final Color color;
  final double size;

  const JanStateIllustration({super.key, required this.icon, this.color = JanColors.primary, this.size = 132});

  @override
  Widget build(BuildContext context) {
    return ExcludeSemantics(
      child: SizedBox.square(
        dimension: size,
        child: Stack(
          alignment: Alignment.center,
          children: [
            Positioned.fill(child: CustomPaint(painter: _OrbitPainter(color: color))),
            Container(
              width: size * 0.56,
              height: size * 0.56,
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.12),
                shape: BoxShape.circle,
                border: Border.all(color: color.withValues(alpha: 0.35), width: 1.5),
              ),
              child: Icon(icon, size: size * 0.28, color: color),
            ),
          ],
        ),
      ),
    );
  }
}

class _OrbitPainter extends CustomPainter {
  final Color color;
  _OrbitPainter({required this.color});

  @override
  void paint(Canvas canvas, Size size) {
    final c = size.center(Offset.zero);
    final r = size.shortestSide * 0.42;
    final ring = Paint()
      ..color = color.withValues(alpha: 0.18)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.2;
    canvas.drawCircle(c, r, ring);
    final angles = [-2.4, -0.7, 0.5, 2.0];
    final pts = angles.map((a) => c + Offset(math.cos(a), math.sin(a)) * r).toList();
    final line = Paint()
      ..color = color.withValues(alpha: 0.30)
      ..strokeWidth = 1.2;
    for (var i = 0; i + 1 < pts.length; i++) {
      canvas.drawLine(pts[i], pts[i + 1], line);
    }
    final colors = [JanColors.sky, JanColors.tealBrand, JanColors.amber, color];
    for (var i = 0; i < pts.length; i++) {
      canvas.drawCircle(pts[i], 4.5, Paint()..color = colors[i % colors.length]);
    }
  }

  @override
  bool shouldRepaint(covariant _OrbitPainter oldDelegate) => oldDelegate.color != color;
}
