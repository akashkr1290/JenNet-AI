import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/complaints/models/complaint_status.dart';

/// Phase 20: unit tests for [ComplaintStatus] - the wire-name <-> enum
/// mapping is the single thing every complaint screen depends on being
/// exactly right against the backend's ComplaintStatus enum
/// (V6__create_complaints.sql's locked value list).
///
/// NOT EXECUTED in this workspace (no `flutter`/`dart` SDK on PATH - see
/// PROJECT_PROGRESS.md's Phase 20 TESTS section). Manually validated
/// against complaint_status.dart's actual enum values/switch statements.
void main() {
  group('ComplaintStatus.fromJson', () {
    test('parses every backend wire value back to the matching enum constant', () {
      const expected = {
        'SUBMITTED': ComplaintStatus.submitted,
        'AI_PROCESSING': ComplaintStatus.aiProcessing,
        'VERIFIED': ComplaintStatus.verified,
        'ASSIGNED': ComplaintStatus.assigned,
        'IN_PROGRESS': ComplaintStatus.inProgress,
        'RESOLVED': ComplaintStatus.resolved,
        'CLOSED': ComplaintStatus.closed,
        'REJECTED': ComplaintStatus.rejected,
        'DUPLICATE': ComplaintStatus.duplicate,
        'ESCALATED': ComplaintStatus.escalated,
        'REOPENED': ComplaintStatus.reopened,
      };
      expected.forEach((wire, status) {
        expect(ComplaintStatus.fromJson(wire), status, reason: 'wire value "$wire"');
      });
    });

    test('an unrecognized wire value falls back to submitted rather than throwing', () {
      expect(ComplaintStatus.fromJson('SOME_FUTURE_STATUS'), ComplaintStatus.submitted);
    });

    test('every enum value round-trips through wireName and back', () {
      for (final status in ComplaintStatus.values) {
        expect(ComplaintStatus.fromJson(status.wireName), status);
      }
    });
  });

  group('ComplaintStatus.label', () {
    test('every enum value has a non-empty citizen-facing label', () {
      for (final status in ComplaintStatus.values) {
        expect(status.label, isNotEmpty);
      }
    });

    test('aiProcessing is labeled with plain language, not the internal wire name', () {
      // SRS Table 10: citizens should never see the raw backend enum name.
      expect(ComplaintStatus.aiProcessing.label, 'Under Review');
      expect(ComplaintStatus.aiProcessing.label, isNot(contains('_')));
    });
  });
}
