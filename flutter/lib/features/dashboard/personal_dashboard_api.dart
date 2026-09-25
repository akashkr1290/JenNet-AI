import '../../core/api/api_client.dart';
import '../../core/api/api_time.dart';

/// Gap-backlog Patches 08/09 (Sep 2026 strict recheck): client for
/// PersonalDashboardController. Both endpoints describe the caller only.
int _i(Object? v) => (v as num?)?.toInt() ?? 0;
double? _dbl(Object? v) => (v as num?)?.toDouble();
DateTime? _dt(Object? v) => parseApiTimestamp(v);

class RecentComplaint {
  final int complaintId;
  final String referenceNumber;
  final String category;
  final String status;
  final String? severity;
  final double? priorityScore;
  final String? aiStatus;
  final double? aiConfidence;
  final DateTime? createdAt;
  final DateTime? slaDueAt;
  final bool slaBreached;

  RecentComplaint.fromJson(Map<String, dynamic> j)
      : complaintId = _i(j['complaintId']),
        referenceNumber = j['referenceNumber'] as String? ?? '',
        category = j['category'] as String? ?? 'GENERAL',
        status = j['status'] as String? ?? 'SUBMITTED',
        severity = j['severity'] as String?,
        priorityScore = _dbl(j['priorityScore']),
        aiStatus = j['aiStatus'] as String?,
        aiConfidence = _dbl(j['aiConfidence']),
        createdAt = _dt(j['createdAt']),
        slaDueAt = _dt(j['slaDueAt']),
        slaBreached = (j['slaBreached'] as bool?) ?? false;
}

class CitizenDashboard {
  final int total, pending, inProgress, resolved, closed, rejected, duplicate;
  final List<RecentComplaint> recentComplaints;

  CitizenDashboard.fromJson(Map<String, dynamic> j)
      : total = _i(j['total']),
        pending = _i(j['pending']),
        inProgress = _i(j['inProgress']),
        resolved = _i(j['resolved']),
        closed = _i(j['closed']),
        rejected = _i(j['rejected']),
        duplicate = _i(j['duplicate']),
        recentComplaints = ((j['recentComplaints'] as List?) ?? [])
            .map((e) => RecentComplaint.fromJson(e as Map<String, dynamic>))
            .toList();
}

class OfficerTask {
  final int complaintId;
  final String referenceNumber;
  final String category;
  final String status;
  final String? severity;
  final DateTime? slaDueAt;
  final bool overdue;

  OfficerTask.fromJson(Map<String, dynamic> j)
      : complaintId = _i(j['complaintId']),
        referenceNumber = j['referenceNumber'] as String? ?? '',
        category = j['category'] as String? ?? 'GENERAL',
        status = j['status'] as String? ?? 'ASSIGNED',
        severity = j['severity'] as String?,
        slaDueAt = _dt(j['slaDueAt']),
        overdue = (j['overdue'] as bool?) ?? false;
}

class OfficerDashboard {
  final int assigned, inProgress, resolved, closed, overdue, atRisk, onTrack, noSla, resolvedLast30Days;
  final double? avgResolutionHoursLast30Days;
  final double? slaCompliancePercentLast30Days;
  final List<OfficerTask> todaysTasks;

  OfficerDashboard.fromJson(Map<String, dynamic> j)
      : assigned = _i(j['assigned']),
        inProgress = _i(j['inProgress']),
        resolved = _i(j['resolved']),
        closed = _i(j['closed']),
        overdue = _i(j['overdue']),
        atRisk = _i(j['atRisk']),
        onTrack = _i(j['onTrack']),
        noSla = _i(j['noSla']),
        resolvedLast30Days = _i(j['resolvedLast30Days']),
        avgResolutionHoursLast30Days = _dbl(j['avgResolutionHoursLast30Days']),
        slaCompliancePercentLast30Days = _dbl(j['slaCompliancePercentLast30Days']),
        todaysTasks = ((j['todaysTasks'] as List?) ?? [])
            .map((e) => OfficerTask.fromJson(e as Map<String, dynamic>))
            .toList();
}

class PersonalDashboardApi {
  PersonalDashboardApi._();
  static final PersonalDashboardApi instance = PersonalDashboardApi._();

  final _client = ApiClient.instance;

  Future<CitizenDashboard> citizen() async =>
      CitizenDashboard.fromJson(await _client.get('/citizen/dashboard') as Map<String, dynamic>);

  Future<OfficerDashboard> officer() async =>
      OfficerDashboard.fromJson(await _client.get('/officer/dashboard') as Map<String, dynamic>);
}
