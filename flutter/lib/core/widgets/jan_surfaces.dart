import 'package:flutter/material.dart';

import '../theme/jan_tokens.dart';
import 'jan_illustrations.dart';

/// The JanNet AI page background: a soft blue gradient with faint geometric
/// facets and an outline skyline along the bottom - the treatment used on
/// every reference screen. Purely decorative (painted behind [child]).
class JanBackdrop extends StatelessWidget {
  final Widget child;
  final bool skyline;

  const JanBackdrop({super.key, required this.child, this.skyline = true});

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xFFEFF5FC), Color(0xFFF6F9FD)],
        ),
      ),
      child: CustomPaint(
        painter: _BackdropPainter(skyline: skyline),
        child: child,
      ),
    );
  }
}

class _BackdropPainter extends CustomPainter {
  final bool skyline;
  _BackdropPainter({required this.skyline});

  @override
  void paint(Canvas canvas, Size size) {
    final facet = Paint()..color = const Color(0x33C9DAEE);
    // Top-right and bottom-left translucent facets.
    final tr = Path()
      ..moveTo(size.width * 0.72, 0)
      ..lineTo(size.width, 0)
      ..lineTo(size.width, size.height * 0.16)
      ..lineTo(size.width * 0.86, size.height * 0.09)
      ..close();
    canvas.drawPath(tr, facet);
    final bl = Path()
      ..moveTo(0, size.height * 0.80)
      ..lineTo(size.width * 0.12, size.height * 0.86)
      ..lineTo(0, size.height * 0.95)
      ..close();
    canvas.drawPath(bl, facet);
    if (skyline && size.height > 320) {
      final w = size.width.clamp(0.0, 520.0);
      const h = 110.0;
      canvas.save();
      canvas.translate(size.width - w, size.height - h);
      SkylinePainter(color: const Color(0x40A9C1DD), windows: false).paint(canvas, Size(w, h));
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(covariant _BackdropPainter oldDelegate) => oldDelegate.skyline != skyline;
}

/// Centres [child] and caps its width - keeps content readable on web and
/// tablets instead of stretching the phone layout edge to edge.
class ResponsiveCenter extends StatelessWidget {
  final Widget child;
  final double maxWidth;

  const ResponsiveCenter({super.key, required this.child, this.maxWidth = JanBreakpoints.contentMaxWidth});

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.topCenter,
      child: ConstrainedBox(constraints: BoxConstraints(maxWidth: maxWidth), child: child),
    );
  }
}

/// White rounded card with the reference's soft shadow. Tappable when
/// [onTap] is set (announced as a button, with [semanticLabel] if given).
class JanCard extends StatelessWidget {
  final Widget child;
  final EdgeInsetsGeometry padding;
  final VoidCallback? onTap;
  final Color? color;
  final Gradient? gradient;
  final Color? borderColor;
  final BorderRadius borderRadius;
  final bool elevated;
  final String? semanticLabel;

  const JanCard({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(JanSpace.md),
    this.onTap,
    this.color,
    this.gradient,
    this.borderColor,
    this.borderRadius = JanRadius.lgAll,
    this.elevated = true,
    this.semanticLabel,
  });

  @override
  Widget build(BuildContext context) {
    Widget content = Padding(padding: padding, child: child);
    if (onTap != null) {
      content = Semantics(
        button: true,
        label: semanticLabel,
        child: InkWell(onTap: onTap, borderRadius: borderRadius, child: content),
      );
    }
    return DecoratedBox(
      decoration: BoxDecoration(
        color: gradient == null ? (color ?? JanColors.white) : null,
        gradient: gradient,
        borderRadius: borderRadius,
        border: Border.all(color: borderColor ?? JanColors.divider),
        boxShadow: elevated ? JanShadows.card : null,
      ),
      child: Material(
        type: MaterialType.transparency,
        borderRadius: borderRadius,
        clipBehavior: Clip.antiAlias,
        child: content,
      ),
    );
  }
}

/// Small uppercase section title with an optional trailing action, as used
/// for "COMPLAINT STATISTICS", "RECENT COMPLAINTS" etc. in the references.
class JanSectionHeader extends StatelessWidget {
  final String title;
  final Widget? trailing;
  final EdgeInsetsGeometry padding;

  const JanSectionHeader({
    super.key,
    required this.title,
    this.trailing,
    this.padding = const EdgeInsets.only(top: JanSpace.lg, bottom: JanSpace.sm),
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: padding,
      child: Row(
        children: [
          Expanded(
            child: Semantics(
              header: true,
              child: Text(
                title.toUpperCase(),
                style: const TextStyle(
                  fontSize: 13.5,
                  fontWeight: FontWeight.w800,
                  letterSpacing: 0.9,
                  color: JanColors.navy,
                ),
              ),
            ),
          ),
          if (trailing != null) trailing!,
        ],
      ),
    );
  }
}

/// Large page heading with optional subtitle ("Report an Issue", "My Complaints").
class JanPageHeading extends StatelessWidget {
  final String title;
  final String? subtitle;

  const JanPageHeading({super.key, required this.title, this.subtitle});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Semantics(
          header: true,
          child: Text(
            title,
            style: theme.textTheme.headlineMedium?.copyWith(color: JanColors.navy, fontWeight: FontWeight.w800),
          ),
        ),
        if (subtitle != null) ...[
          const SizedBox(height: 4),
          Text(subtitle!, style: theme.textTheme.bodyLarge?.copyWith(color: JanColors.muted)),
        ],
      ],
    );
  }
}

/// A visible field label placed above an input, as in the reference forms.
class JanFieldLabel extends StatelessWidget {
  final String text;
  const JanFieldLabel(this.text, {super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6, left: 2),
      child: Text(
        text,
        style: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w600, color: JanColors.slate),
      ),
    );
  }
}

/// Standard pushed-page scaffold: themed app bar, JanNet backdrop, content
/// centred and width-capped for web/tablet.
class JanPage extends StatelessWidget {
  final String title;
  final Widget body;
  final List<Widget>? actions;
  final double maxWidth;
  final Widget? bottomBar;
  final Widget? floatingActionButton;

  const JanPage({
    super.key,
    required this.title,
    required this.body,
    this.actions,
    this.maxWidth = JanBreakpoints.contentMaxWidth,
    this.bottomBar,
    this.floatingActionButton,
  });

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(title), actions: actions),
      body: JanBackdrop(child: ResponsiveCenter(maxWidth: maxWidth, child: body)),
      bottomNavigationBar: bottomBar,
      floatingActionButton: floatingActionButton,
    );
  }
}
