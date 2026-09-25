import '../../core/api/api_client.dart';

/// Audit GAP-039 (SRS 15.12 / 21): client for the period reports
/// (GET /api/v1/reports/period and /period.csv). The backend validates the
/// range (max 1 year by default), scopes a Department Head to their own
/// department and stores every report as an immutable snapshot.
class PeriodReportSummary {
  final String reportType;
  final String periodStart;
  final String periodEnd;
  final String? departmentName;
  final int? snapshotId;
  final bool insufficientData;
  final Map<String, int> counts;
  final double? slaCompliancePercent;
  final double? averageResolutionHours;
  final List<MapEntry<String, int>> byCategory;
  final List<MapEntry<String, int>> byWard;

  PeriodReportSummary({
    required this.reportType,
    required this.periodStart,
    required this.periodEnd,
    this.departmentName,
    this.snapshotId,
    required this.insufficientData,
    required this.counts,
    this.slaCompliancePercent,
    this.averageResolutionHours,
    required this.byCategory,
    required this.byWard,
  });

  factory PeriodReportSummary.fromJson(Map<String, dynamic> json) {
    final c = (json['counts'] as Map<String, dynamic>?) ?? const {};
    final sla = (json['sla'] as Map<String, dynamic>?) ?? const {};
    return PeriodReportSummary(
      reportType: json['reportType'] as String,
      periodStart: json['periodStart'] as String,
      periodEnd: json['periodEnd'] as String,
      departmentName: json['departmentName'] as String?,
      snapshotId: (json['snapshotId'] as num?)?.toInt(),
      insufficientData: (json['insufficientData'] as bool?) ?? false,
      counts: {for (final e in c.entries) e.key: (e.value as num).toInt()},
      slaCompliancePercent: (sla['compliancePercent'] as num?)?.toDouble(),
      averageResolutionHours: (json['averageResolutionHours'] as num?)?.toDouble(),
      byCategory: [
        for (final r in (json['byCategory'] as List? ?? const []))
          MapEntry((r as Map<String, dynamic>)['category'] as String, (r['received'] as num).toInt()),
      ],
      byWard: [
        for (final r in (json['byWard'] as List? ?? const []))
          MapEntry(((r as Map<String, dynamic>)['wardName'] as String?) ?? '-', (r['received'] as num).toInt()),
      ],
    );
  }
}

String _date(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// Query for a report: [type] DAILY | WEEKLY | CUSTOM (CUSTOM needs both dates).
Map<String, dynamic> periodReportQuery(String type, DateTime? from, DateTime? to, int? departmentId) => {
      'type': type,
      if (from != null) 'from': _date(from),
      if (to != null) 'to': _date(to),
      if (departmentId != null) 'departmentId': departmentId,
    };

class ReportApi {
  ReportApi._();
  static final ReportApi instance = ReportApi._();
  final _client = ApiClient.instance;

  Future<PeriodReportSummary> period(String type, {DateTime? from, DateTime? to, int? departmentId}) async {
    final json = await _client.get('/reports/period', query: periodReportQuery(type, from, to, departmentId))
        as Map<String, dynamic>;
    return PeriodReportSummary.fromJson(json);
  }

  Future<String> periodCsv(String type, {DateTime? from, DateTime? to, int? departmentId}) =>
      _client.getRaw('/reports/period.csv', query: periodReportQuery(type, from, to, departmentId));
}
