import 'package:flutter/material.dart';

import '../../../core/theme/jan_tokens.dart';

/// Shared dashboard count tile (Patches 08/09). Semantics give screen readers
/// one clear "label: value" announcement (Patch 46).
///
/// UI redesign: restyled as a white JanNet card with a tinted icon chip.
/// New screens use JanStatCard; this compact variant is kept for callers
/// that pass an arbitrary accent colour.
class StatTile extends StatelessWidget {
  final String label;
  final int value;
  final Color color;
  final IconData icon;
  const StatTile({super.key, required this.label, required this.value, required this.color, required this.icon});

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: '$label: $value',
      excludeSemantics: true,
      child: Container(
        width: 150,
        padding: const EdgeInsets.all(JanSpace.sm),
        decoration: BoxDecoration(
          color: JanColors.white,
          borderRadius: JanRadius.lgAll,
          border: Border.all(color: JanColors.divider),
          boxShadow: JanShadows.card,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 36,
              height: 36,
              decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: JanRadius.smAll),
              child: Icon(icon, color: color, size: 20),
            ),
            const SizedBox(height: JanSpace.xs),
            Text('$value', style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w800, color: JanColors.navy)),
            Text(label, style: const TextStyle(fontSize: 12.5, color: JanColors.muted, fontWeight: FontWeight.w600)),
          ],
        ),
      ),
    );
  }
}
