import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/complaints/models/complaint.dart';
import 'package:jannet_ai/features/complaints/widgets/ai_decision_steps.dart';

void main() {
  testWidgets('renders the three steps with the YOLO threshold', (tester) async {
    final d = AiDecision.fromJson({
      'outcome': 'GEMINI_VERIFIED', 'yoloThreshold': 50, 'yoloPassed': false,
      'yoloClass': 'garbage_overflow', 'yoloConfidence': 47, 'geminiUsed': true,
      'geminiCategory': 'GARBAGE_OVERFLOW', 'autoApproveThreshold': 85, 'autoApproved': false,
    });
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: AiDecisionSteps(decision: d))));
    expect(find.textContaining('Best guess garbage overflow 47% - below the 50% threshold', findRichText: true),
        findsOneWidget);
    expect(find.textContaining('Identified garbage overflow', findRichText: true), findsOneWidget);
    expect(find.textContaining('Verified by Gemini', findRichText: true), findsOneWidget);
    expect(find.byIcon(Icons.cancel_rounded), findsOneWidget);
    expect(find.byIcon(Icons.check_circle_rounded), findsNWidgets(2));
  });
}
