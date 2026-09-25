import 'package:flutter/material.dart';

import '../theme/jan_tokens.dart';

/// Remaining-gaps item 14 (accessibility): form/screen error messages are a
/// live region (announced when they appear or change). UI redesign: shown as
/// a tinted banner with an error icon - meaning is carried by the icon and
/// text, not colour alone. The message itself is still a plain [Text].
class ErrorText extends StatelessWidget {
  final String message;
  const ErrorText(this.message, {super.key});

  @override
  Widget build(BuildContext context) {
    return Semantics(
      liveRegion: true,
      container: true,
      label: 'Error: $message',
      excludeSemantics: true,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: JanColors.errorLight,
          borderRadius: JanRadius.mdAll,
          border: Border.all(color: JanColors.error.withValues(alpha: 0.3)),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(Icons.error_outline_rounded, color: JanColors.error, size: 20),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                message,
                style: const TextStyle(color: JanColors.error, fontWeight: FontWeight.w600, height: 1.4),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
