import 'package:flutter/material.dart';

/// Shared dashboard count tile (Patches 08/09). Semantics give screen readers
/// one clear "label: value" announcement (Patch 46).
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
      child: SizedBox(
        width: 150,
        child: Card(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(icon, color: color),
                const SizedBox(height: 6),
                Text('$value', style: Theme.of(context).textTheme.headlineSmall),
                Text(label, style: Theme.of(context).textTheme.bodySmall),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
