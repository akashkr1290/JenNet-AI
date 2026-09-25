import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/reports/period_report.dart';

/// Audit GAP-039: report query and response parsing. NOT EXECUTED in the
/// workspace that wrote it (no Flutter SDK there) - run with `flutter test`.
void main() {
  test('query uses ISO dates and omits what is not set', () {
    expect(periodReportQuery('CUSTOM', DateTime(2026, 9, 1), DateTime(2026, 9, 7), 3),
        {'type': 'CUSTOM', 'from': '2026-09-01', 'to': '2026-09-07', 'departmentId': 3});
    expect(periodReportQuery('WEEKLY', null, null, null), {'type': 'WEEKLY'});
  });

  test('parses a period report including the insufficient-data label', () {
    final r = PeriodReportSummary.fromJson({
      'reportType': 'WEEKLY',
      'periodStart': '2026-09-15',
      'periodEnd': '2026-09-21',
      'departmentName': 'Roads',
      'snapshotId': 12,
      'insufficientData': true,
      'counts': {'received': 0, 'resolved': 0},
      'sla': {'compliancePercent': null},
      'averageResolutionHours': null,
      'byCategory': [
        {'category': 'POTHOLE', 'received': 2, 'resolved': 1}
      ],
      'byWard': [
        {'wardId': 1, 'wardName': 'Ward 1', 'received': 2}
      ],
    });
    expect(r.insufficientData, isTrue);
    expect(r.snapshotId, 12);
    expect(r.counts['received'], 0);
    expect(r.byCategory.single.value, 2);
    expect(r.byWard.single.key, 'Ward 1');
  });
}
