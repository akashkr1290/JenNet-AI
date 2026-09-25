import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/complaints/models/complaint.dart';
import 'package:jannet_ai/features/complaints/sla_countdown.dart';

/// Audit GAP-040: SLA countdown labels and the summary's slaDueAt field.
/// NOT EXECUTED in the workspace that wrote it (no Flutter SDK there).
void main() {
  final now = DateTime(2026, 9, 25, 12);

  test('no deadline, no countdown', () {
    expect(SlaCountdown.of(null, now), isNull);
  });

  test('time left, due soon under 24 hours', () {
    final far = SlaCountdown.of(now.add(const Duration(days: 2, hours: 3)), now)!;
    expect(far.label, 'Due in 2d 3h');
    expect(far.dueSoon, isFalse);
    final soon = SlaCountdown.of(now.add(const Duration(hours: 5, minutes: 30)), now)!;
    expect(soon.label, 'Due in 5h 30m');
    expect(soon.dueSoon, isTrue);
    expect(soon.overdue, isFalse);
  });

  test('overdue', () {
    final late = SlaCountdown.of(now.subtract(const Duration(minutes: 45)), now)!;
    expect(late.overdue, isTrue);
    expect(late.label, 'Overdue by 45m');
  });

  test('ComplaintSummary reads slaDueAt (UTC)', () {
    final c = ComplaintSummary.fromJson({
      'complaintId': 1,
      'referenceNumber': 'JN-2026-000001',
      'category': 'POTHOLE',
      'status': 'ASSIGNED',
      'slaDueAt': '2026-09-26T10:00:00Z',
    });
    expect(c.slaDueAt, DateTime.utc(2026, 9, 26, 10).toLocal());
    expect(complaintQueueSorts.keys, containsAll(['NEWEST', 'SEVERITY', 'SLA_DUE']));
  });
}
