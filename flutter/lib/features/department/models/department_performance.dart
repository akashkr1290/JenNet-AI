import '../../../core/api/api_time.dart';

/// Mirrors UserProfileResponse, but only the fields the officer picker /
/// workload table need. See UserApi.UserProfile for the citizen-facing
/// equivalent kept in a separate file (that one is "my own profile",
/// this one is "another user's profile" - deliberately not shared, so a
/// future change to one doesn't silently affect the other's field set).
class OfficerSummary {
  final int userId;
  final String fullName;
  final String? email;
  final String? mobileNumber;

  /// Pilot workflow 2026-09-30 (the Department Head assigns officers): the
  /// officer's own availability setting - AVAILABLE, BUSY or ON_LEAVE.
  final String availability;

  /// Complaints the officer currently has open (Assigned or In Progress).
  final int openComplaints;

  OfficerSummary({
    required this.userId,
    required this.fullName,
    this.email,
    this.mobileNumber,
    this.availability = 'AVAILABLE',
    this.openComplaints = 0,
  });

  factory OfficerSummary.fromJson(Map<String, dynamic> json) {
    return OfficerSummary(
      userId: json['userId'] as int,
      fullName: json['fullName'] as String,
      email: json['email'] as String?,
      mobileNumber: json['mobileNumber'] as String?,
      availability: (json['availability'] as String?) ?? 'AVAILABLE',
      openComplaints: (json['openComplaints'] as num?)?.toInt() ?? 0,
    );
  }

  String get availabilityLabel => switch (availability) {
        'BUSY' => 'Busy',
        'ON_LEAVE' => 'On leave',
        _ => 'Available',
      };

  /// e.g. "Asha Rao · Available · 2 open"
  String get pickerLabel => '$fullName · $availabilityLabel · $openComplaints open';
}

/// Mirrors OfficerWorkloadResponse (backend, dto/department/) - one row
/// of the Department Performance View's officer workload table (SRS
/// 16.2).
class OfficerWorkload {
  final int officerId;
  final String officerName;
  final int assignedOpenCount;
  final int resolvedCount;
  final double? avgResolutionHours;
  final int slaBreachCount;

  OfficerWorkload({
    required this.officerId,
    required this.officerName,
    required this.assignedOpenCount,
    required this.resolvedCount,
    this.avgResolutionHours,
    required this.slaBreachCount,
  });

  factory OfficerWorkload.fromJson(Map<String, dynamic> json) {
    return OfficerWorkload(
      officerId: json['officerId'] as int,
      officerName: json['officerName'] as String,
      assignedOpenCount: (json['assignedOpenCount'] as num?)?.toInt() ?? 0,
      resolvedCount: (json['resolvedCount'] as num?)?.toInt() ?? 0,
      avgResolutionHours: (json['avgResolutionHours'] as num?)?.toDouble(),
      slaBreachCount: (json['slaBreachCount'] as num?)?.toInt() ?? 0,
    );
  }
}

/// Mirrors DepartmentPerformanceResponse (backend, dto/department/) -
/// the full SRS 16.2 "Department Performance View" payload: KPI tiles +
/// SLA compliance + officer workload table.
class DepartmentPerformance {
  final int departmentId;
  final String departmentName;
  final int totalComplaints;
  final int openComplaints;
  final int resolvedComplaints;
  final int escalatedComplaints;
  final double slaCompliancePercent;
  final double? avgResolutionHours;
  final List<OfficerWorkload> officerWorkloads;
  final DateTime? generatedAt;

  DepartmentPerformance({
    required this.departmentId,
    required this.departmentName,
    required this.totalComplaints,
    required this.openComplaints,
    required this.resolvedComplaints,
    required this.escalatedComplaints,
    required this.slaCompliancePercent,
    this.avgResolutionHours,
    required this.officerWorkloads,
    this.generatedAt,
  });

  factory DepartmentPerformance.fromJson(Map<String, dynamic> json) {
    return DepartmentPerformance(
      departmentId: json['departmentId'] as int,
      departmentName: json['departmentName'] as String,
      totalComplaints: (json['totalComplaints'] as num?)?.toInt() ?? 0,
      openComplaints: (json['openComplaints'] as num?)?.toInt() ?? 0,
      resolvedComplaints: (json['resolvedComplaints'] as num?)?.toInt() ?? 0,
      escalatedComplaints: (json['escalatedComplaints'] as num?)?.toInt() ?? 0,
      slaCompliancePercent: (json['slaCompliancePercent'] as num?)?.toDouble() ?? 100.0,
      avgResolutionHours: (json['avgResolutionHours'] as num?)?.toDouble(),
      officerWorkloads: ((json['officerWorkloads'] as List?) ?? [])
          .map((e) => OfficerWorkload.fromJson(e as Map<String, dynamic>))
          .toList(),
      generatedAt: json['generatedAt'] != null ? parseApiTimestamp(json['generatedAt']) : null,
    );
  }
}
