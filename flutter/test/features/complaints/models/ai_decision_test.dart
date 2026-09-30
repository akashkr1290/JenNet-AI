import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/complaints/models/complaint.dart';

/// Pilot request (2026-09-28): show the YOLO threshold, then Gemini, then
/// manual review, as plain steps.
void main() {
  AiDecision decision(Map<String, dynamic> overrides) => AiDecision.fromJson({
        'outcome': 'MANUAL_REVIEW',
        'yoloThreshold': 50.0,
        'yoloPassed': false,
        'geminiUsed': true,
        'autoApproveThreshold': 85.0,
        'autoApproved': false,
        ...overrides,
      });

  test('AiClassification reads the decision, and tolerates its absence', () {
    final withDecision = AiClassification.fromJson({
      'confidence': 0,
      'aiStatus': 'MANUAL_REVIEW_REQUIRED',
      'decision': {'outcome': 'GEMINI_VERIFIED', 'yoloThreshold': 50, 'geminiCategory': 'OPEN_MANHOLE'},
    });
    expect(withDecision.decision!.outcome, 'GEMINI_VERIFIED');
    expect(AiClassification.fromJson({'aiStatus': 'MANUAL_REVIEW_REQUIRED'}).decision, isNull);
  });

  test('YOLO passed: shows class, score and threshold', () {
    final d = decision({
      'outcome': 'YOLO_CONFIRMED_BY_GEMINI', 'yoloPassed': true, 'yoloClass': 'pothole',
      'yoloConfidence': 75.3, 'geminiAgrees': true, 'geminiCategory': 'POTHOLE',
    });
    expect(d.yoloStep.detail, 'pothole 75% (threshold 50%) - passed');
    expect(d.yoloStep.state, AiStepState.passed);
    expect(d.geminiStep.detail, 'Agrees: pothole');
    expect(d.resultStep.detail, contains('Detected by YOLO'));
    expect(d.needsManualReview, isTrue);
  });

  test('YOLO below threshold, Gemini verified', () {
    final d = decision({
      'outcome': 'GEMINI_VERIFIED', 'yoloClass': 'open_manhole', 'yoloConfidence': 40.2,
      'geminiCategory': 'OPEN_MANHOLE',
    });
    expect(d.yoloStep.detail, 'Best guess open manhole 40% - below the 50% threshold');
    expect(d.yoloStep.state, AiStepState.failed);
    expect(d.geminiStep.detail, 'Identified open manhole');
    expect(d.geminiStep.state, AiStepState.passed);
    expect(d.resultStep.detail, 'Verified by Gemini - the Verification Team will confirm');
  });

  test('both failed: manual review', () {
    final d = decision({'geminiCategory': 'GENERAL'});
    expect(d.yoloStep.detail, 'Nothing detected (threshold 50%)');
    expect(d.geminiStep.detail, 'Could not identify the issue');
    expect(d.geminiStep.state, AiStepState.failed);
    expect(d.resultStep.detail, 'YOLO and Gemini could not identify it - Verification Team review');
    expect(d.resultStep.state, AiStepState.failed);
  });

  test('Gemini unavailable shows the reason', () {
    final d = decision({'geminiUsed': false, 'geminiUnavailableReason': 'timeout'});
    expect(d.geminiStep.detail, 'Not available (timeout)');
    expect(d.geminiStep.state, AiStepState.skipped);
  });

  test('Gemini revised YOLO', () {
    final d = decision({
      'outcome': 'GEMINI_REVISED', 'yoloPassed': true, 'yoloClass': 'open_manhole', 'yoloConfidence': 83,
      'geminiAgrees': false, 'geminiCategory': 'POTHOLE',
    });
    expect(d.geminiStep.detail, 'Disagrees - suggests pothole');
    expect(d.resultStep.detail, 'Category revised by Gemini - the Verification Team will confirm');
  });

  test('model unavailable and auto-approved', () {
    expect(decision({'outcome': 'MODEL_UNAVAILABLE'}).yoloStep.detail, 'Detection model unavailable');
    final auto = decision({'outcome': 'YOLO_CONFIRMED_BY_GEMINI', 'yoloPassed': true, 'yoloClass': 'pothole',
      'yoloConfidence': 95, 'geminiAgrees': true, 'autoApproved': true});
    expect(auto.resultStep.detail, 'Accepted automatically (confidence at least 85%) - sent to the department');
    expect(auto.needsManualReview, isFalse);
    expect(auto.steps, hasLength(3));
  });
}
