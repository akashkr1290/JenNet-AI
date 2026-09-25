import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../theme/jan_tokens.dart';

/// Segmented one-time-code input (reference "Verify your number" screen).
///
/// Built on ONE real TextField (transparent, laid over the boxes), so paste,
/// SMS one-time-code autofill, hardware keyboards and Flutter Web all behave
/// like a normal input, and the caller keeps using a plain
/// TextEditingController exactly as before. The boxes are only a rendering
/// of that field's value.
class JanOtpInput extends StatefulWidget {
  final TextEditingController controller;
  final int length;
  final bool hasError;
  final bool autofocus;
  final String semanticLabel;
  final ValueChanged<String>? onCompleted;

  const JanOtpInput({
    super.key,
    required this.controller,
    this.length = 6,
    this.hasError = false,
    this.autofocus = true,
    this.semanticLabel = 'OTP Code',
    this.onCompleted,
  });

  @override
  State<JanOtpInput> createState() => _JanOtpInputState();
}

class _JanOtpInputState extends State<JanOtpInput> {
  final _focusNode = FocusNode();
  String? _lastCompleted;

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onChanged);
    _focusNode.addListener(_onFocusChanged);
  }

  @override
  void didUpdateWidget(covariant JanOtpInput oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_onChanged);
      widget.controller.addListener(_onChanged);
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onChanged);
    _focusNode.removeListener(_onFocusChanged);
    _focusNode.dispose();
    super.dispose();
  }

  void _onFocusChanged() {
    if (mounted) setState(() {});
  }

  void _onChanged() {
    if (!mounted) return;
    setState(() {});
    final text = widget.controller.text;
    // Fire once per distinct complete code (not on every rebuild/focus change).
    if (text.length == widget.length && text != _lastCompleted) {
      _lastCompleted = text;
      widget.onCompleted?.call(text);
    } else if (text.length < widget.length) {
      _lastCompleted = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final text = widget.controller.text;
    return LayoutBuilder(builder: (context, constraints) {
      const gap = 10.0;
      final boxWidth = ((constraints.maxWidth - gap * (widget.length - 1)) / widget.length).clamp(36.0, 58.0);
      final boxHeight = boxWidth * 1.18;
      final rowWidth = boxWidth * widget.length + gap * (widget.length - 1);
      return Center(
        child: SizedBox(
          width: rowWidth,
          height: boxHeight,
          child: Stack(
            children: [
              ExcludeSemantics(
                child: Row(
                  children: [
                    for (var i = 0; i < widget.length; i++) ...[
                      if (i > 0) const SizedBox(width: gap),
                      _box(i, text, boxWidth, boxHeight),
                    ],
                  ],
                ),
              ),
              Positioned.fill(
                child: Opacity(
                  opacity: 0.0,
                  alwaysIncludeSemantics: true,
                  child: TextField(
                    controller: widget.controller,
                    focusNode: _focusNode,
                    autofocus: widget.autofocus,
                    keyboardType: TextInputType.number,
                    textInputAction: TextInputAction.done,
                    autofillHints: const [AutofillHints.oneTimeCode],
                    inputFormatters: [
                      FilteringTextInputFormatter.digitsOnly,
                      LengthLimitingTextInputFormatter(widget.length),
                    ],
                    showCursor: false,
                    enableSuggestions: false,
                    autocorrect: false,
                    style: const TextStyle(color: Colors.transparent, fontSize: 1),
                    decoration: InputDecoration(
                      labelText: widget.semanticLabel,
                      filled: false,
                      border: InputBorder.none,
                      enabledBorder: InputBorder.none,
                      focusedBorder: InputBorder.none,
                      errorBorder: InputBorder.none,
                      focusedErrorBorder: InputBorder.none,
                      counterText: '',
                      isCollapsed: true,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      );
    });
  }

  Widget _box(int i, String text, double w, double h) {
    final filled = i < text.length;
    final active = _focusNode.hasFocus && (i == text.length || (i == widget.length - 1 && text.length == widget.length));
    Color border = JanColors.border;
    Color fill = JanColors.white;
    Color digit = JanColors.navy;
    if (widget.hasError) {
      border = JanColors.amber;
      fill = JanColors.amberLight;
    } else if (filled) {
      border = JanColors.teal;
      fill = JanColors.teal;
      digit = JanColors.white;
    } else if (active) {
      border = JanColors.tealBrand;
    }
    return Container(
      width: w,
      height: h,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: fill,
        borderRadius: JanRadius.mdAll,
        border: Border.all(color: border, width: active || widget.hasError ? 2 : 1.4),
        boxShadow: active ? const [BoxShadow(color: Color(0x3338A9A2), blurRadius: 12)] : null,
      ),
      child: filled
          ? Text(text[i], style: TextStyle(fontSize: w * 0.5, fontWeight: FontWeight.w800, color: digit))
          : (active
              ? Container(width: 2, height: h * 0.42, color: JanColors.tealBrand)
              : const SizedBox.shrink()),
    );
  }
}

/// Live password-strength meter for the registration/reset forms. It scores
/// exactly the rules the backend already enforces (8+ characters, upper,
/// lower, digit, special character) and lists each rule as text, so the
/// feedback never relies on colour alone.
class JanPasswordStrength extends StatelessWidget {
  final TextEditingController controller;
  const JanPasswordStrength({super.key, required this.controller});

  static List<(String, bool)> rules(String p) => [
        ('8+ characters', p.length >= 8),
        ('Upper case', RegExp(r'[A-Z]').hasMatch(p)),
        ('Lower case', RegExp(r'[a-z]').hasMatch(p)),
        ('A digit', RegExp(r'\d').hasMatch(p)),
        ('A symbol', RegExp(r'[^a-zA-Z0-9]').hasMatch(p)),
      ];

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<TextEditingValue>(
      valueListenable: controller,
      builder: (context, value, _) {
        final checks = rules(value.text);
        final score = checks.where((c) => c.$2).length;
        final (String label, Color color) = switch (score) {
          5 => ('Strong', JanColors.teal),
          4 => ('Good', JanColors.primary),
          3 => ('Fair', JanColors.amberDark),
          _ => ('Weak', JanColors.error),
        };
        if (value.text.isEmpty) return const SizedBox.shrink();
        return Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Semantics(
                label: 'Password strength: $label',
                excludeSemantics: true,
                child: Row(
                  children: [
                    Expanded(
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(4),
                        child: LinearProgressIndicator(value: score / 5, minHeight: 6, color: color),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Text(label, style: TextStyle(color: color, fontWeight: FontWeight.w700)),
                  ],
                ),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 12,
                runSpacing: 4,
                children: [
                  for (final c in checks)
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(c.$2 ? Icons.check_circle_rounded : Icons.radio_button_unchecked,
                            size: 16, color: c.$2 ? JanColors.teal : JanColors.muted),
                        const SizedBox(width: 4),
                        Text(c.$1, style: TextStyle(fontSize: 12.5, color: c.$2 ? JanColors.teal : JanColors.muted)),
                      ],
                    ),
                ],
              ),
            ],
          ),
        );
      },
    );
  }
}
