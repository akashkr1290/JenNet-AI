import 'package:flutter/material.dart';

import '../../../core/theme/jan_tokens.dart';
import '../models/complaint.dart';

/// The AI decision path as three steps: YOLO detection (with its threshold),
/// Gemini verification, result (pilot request 2026-09-28).
class AiDecisionSteps extends StatelessWidget {
  final AiDecision decision;

  const AiDecisionSteps({super.key, required this.decision});

  @override
  Widget build(BuildContext context) {
    final steps = decision.steps;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (var i = 0; i < steps.length; i++)
          Padding(
            padding: EdgeInsets.only(top: i == 0 ? 0 : JanSpace.xs),
            child: _StepRow(step: steps[i], number: i + 1),
          ),
      ],
    );
  }
}

class _StepRow extends StatelessWidget {
  final AiStep step;
  final int number;

  const _StepRow({required this.step, required this.number});

  @override
  Widget build(BuildContext context) {
    final (IconData icon, Color color, String state) = switch (step.state) {
      AiStepState.passed => (Icons.check_circle_rounded, JanColors.teal, 'passed'),
      AiStepState.failed => (Icons.cancel_rounded, JanColors.error, 'failed'),
      AiStepState.skipped => (Icons.remove_circle_outline_rounded, JanColors.muted, 'skipped'),
    };
    return Semantics(
      label: 'Step $number, ${step.title}, $state: ${step.detail}',
      excludeSemantics: true,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 18, color: color),
          const SizedBox(width: 6),
          Expanded(
            child: Text.rich(
              TextSpan(children: [
                TextSpan(
                  text: '${step.title}: ',
                  style: const TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy),
                ),
                TextSpan(text: step.detail, style: const TextStyle(color: JanColors.ink, height: 1.35)),
              ]),
            ),
          ),
        ],
      ),
    );
  }
}
