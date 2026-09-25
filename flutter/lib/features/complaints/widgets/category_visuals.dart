import 'package:flutter/material.dart';

import '../../../core/theme/jan_tokens.dart';

/// Icon, colours and human label for each backend ComplaintCategory
/// (POTHOLE, GARBAGE_OVERFLOW, WATER_LEAKAGE, BROKEN_STREET_LIGHT,
/// OPEN_MANHOLE, ILLEGAL_CONSTRUCTION, GENERAL), following the reference
/// iconography row. Unknown values fall back to GENERAL.
class CategoryVisual {
  final String label;
  final IconData icon;
  final Color color;
  final Color background;

  const CategoryVisual(this.label, this.icon, this.color, this.background);

  static CategoryVisual of(String? category) {
    switch (category) {
      case 'POTHOLE':
        return const CategoryVisual('Pothole', Icons.edit_road_rounded, JanColors.teal, JanColors.tealLight);
      case 'GARBAGE_OVERFLOW':
        return const CategoryVisual('Garbage', Icons.delete_outline_rounded, JanColors.amberDark, JanColors.amberLight);
      case 'WATER_LEAKAGE':
        return const CategoryVisual('Water Leakage', Icons.water_drop_outlined, JanColors.primary, JanColors.infoLight);
      case 'BROKEN_STREET_LIGHT':
        return const CategoryVisual('Street Light', Icons.light_outlined, Color(0xFF9A4E0E), Color(0xFFFCE9D8));
      case 'OPEN_MANHOLE':
        return const CategoryVisual('Open Manhole', Icons.radio_button_checked_rounded, JanColors.slate, Color(0xFFE7ECF2));
      case 'ILLEGAL_CONSTRUCTION':
        return const CategoryVisual('Illegal Construction', Icons.domain_disabled_outlined, JanColors.navy, Color(0xFFE3E9F2));
      default:
        return CategoryVisual(
          _titleCase(category ?? 'General'),
          Icons.report_outlined,
          JanColors.muted,
          const Color(0xFFEDF1F6),
        );
    }
  }

  static String _titleCase(String raw) => raw
      .toLowerCase()
      .split('_')
      .where((w) => w.isNotEmpty)
      .map((w) => w[0].toUpperCase() + w.substring(1))
      .join(' ');
}

/// Square tinted tile holding the category icon (used where a complaint has
/// no photo URL in the list/summary data).
class CategoryTile extends StatelessWidget {
  final String? category;
  final double size;

  const CategoryTile({super.key, required this.category, this.size = 64});

  @override
  Widget build(BuildContext context) {
    final v = CategoryVisual.of(category);
    return Semantics(
      label: 'Category: ${v.label}',
      excludeSemantics: true,
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          color: v.background,
          borderRadius: JanRadius.mdAll,
          border: Border.all(color: v.color.withValues(alpha: 0.25)),
        ),
        child: Icon(v.icon, color: v.color, size: size * 0.46),
      ),
    );
  }
}
