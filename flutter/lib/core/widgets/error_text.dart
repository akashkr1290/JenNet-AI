import 'package:flutter/material.dart';

/// Remaining-gaps item 14 (accessibility): form/screen error messages were
/// plain red Text - visible, but never announced by TalkBack/VoiceOver, and a
/// hard-coded red that ignored the high-contrast theme. This widget marks the
/// message as a live region (announced when it appears or changes) and uses
/// the theme's error colour.
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
      child: Text(message, style: TextStyle(color: Theme.of(context).colorScheme.error)),
    );
  }
}
