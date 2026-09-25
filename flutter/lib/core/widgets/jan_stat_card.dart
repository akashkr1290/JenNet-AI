import 'package:flutter/material.dart';

import '../theme/jan_tokens.dart';

/// Colour tones for statistic cards (reference: navy "Total", amber
/// "Pending", blue "In Progress", teal "Resolved"). Every tone pairs its
/// background with a text colour at >= 4.5:1 contrast.
enum JanTone { navy, amber, blue, teal, slate, light }

extension JanToneColors on JanTone {
  Color get background => switch (this) {
        JanTone.navy => JanColors.navy,
        JanTone.amber => JanColors.amber,
        JanTone.blue => JanColors.primary,
        JanTone.teal => JanColors.teal,
        JanTone.slate => JanColors.slate,
        JanTone.light => JanColors.white,
      };

  Color get foreground => switch (this) {
        JanTone.amber => JanColors.navy,
        JanTone.light => JanColors.navy,
        _ => JanColors.white,
      };
}

/// Filled statistic card: icon, large value, label. Read by screen readers
/// as one phrase ("Pending: 8").
class JanStatCard extends StatelessWidget {
  final String label;
  final String value;
  final IconData icon;
  final JanTone tone;
  final String? caption;
  final VoidCallback? onTap;

  const JanStatCard({
    super.key,
    required this.label,
    required this.value,
    required this.icon,
    this.tone = JanTone.navy,
    this.caption,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final fg = tone.foreground;
    final card = Container(
      constraints: const BoxConstraints(minHeight: 112),
      padding: const EdgeInsets.all(JanSpace.md),
      decoration: BoxDecoration(
        color: tone.background,
        borderRadius: JanRadius.lgAll,
        border: tone == JanTone.light ? Border.all(color: JanColors.divider) : null,
        boxShadow: JanShadows.card,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, color: fg, size: 26),
          const SizedBox(height: JanSpace.sm),
          FittedBox(
            fit: BoxFit.scaleDown,
            alignment: Alignment.centerLeft,
            child: Text(
              value,
              style: TextStyle(fontSize: 30, fontWeight: FontWeight.w800, color: fg, height: 1.05),
            ),
          ),
          const SizedBox(height: 2),
          Text(
            label,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600, color: fg.withValues(alpha: 0.92)),
          ),
          if (caption != null)
            Text(caption!, style: TextStyle(fontSize: 12, color: fg.withValues(alpha: 0.85))),
        ],
      ),
    );
    return Semantics(
      label: '$label: $value${caption != null ? ', $caption' : ''}',
      button: onTap != null,
      excludeSemantics: true,
      child: onTap == null
          ? card
          : Material(
              type: MaterialType.transparency,
              child: InkWell(onTap: onTap, borderRadius: JanRadius.lgAll, child: card),
            ),
    );
  }
}

/// Lays stat cards out as a responsive grid: 2 columns on phones, up to
/// [maxColumns] on wider screens.
class JanStatGrid extends StatelessWidget {
  final List<Widget> children;
  final int maxColumns;

  const JanStatGrid({super.key, required this.children, this.maxColumns = 4});

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      final width = constraints.maxWidth;
      final columns = (width >= 900 ? maxColumns : (width >= 560 ? 3 : 2)).clamp(1, maxColumns);
      const gap = JanSpace.sm;
      // Floored so rounding never pushes the last card onto a new row.
      final itemWidth = ((width - gap * (columns - 1)) / columns).floorToDouble();
      return Wrap(
        spacing: gap,
        runSpacing: gap,
        children: [for (final c in children) SizedBox(width: itemWidth, child: c)],
      );
    });
  }
}
