/// Phase 16 (Government Dashboard Module, SRS 15.10; Analytics Module,
/// SRS 15.14). Mirrors backend dto/dashboard/ record-for-record - see
/// each backend record's own Javadoc for the exact scope/formula
/// decisions behind these fields; not re-explained here.
class KpiTiles {
  final int totalComplaints;
  final int openComplaints;
  final int resolvedComplaints;
  final int escalatedComplaints;
  final double slaCompliancePercent;
  final double? avgResolutionHours;

  KpiTiles({
    required this.totalComplaints,
    required this.openComplaints,
    required this.resolvedComplaints,
    required this.escalatedComplaints,
    required this.slaCompliancePercent,
    this.avgResolutionHours,
  });

  factory KpiTiles.fromJson(Map<String, dynamic> json) {
    return KpiTiles(
      totalComplaints: json['totalComplaints'] as int,
      openComplaints: json['openComplaints'] as int,
      resolvedComplaints: json['resolvedComplaints'] as int,
      escalatedComplaints: json['escalatedComplaints'] as int,
      slaCompliancePercent: (json['slaCompliancePercent'] as num).toDouble(),
      avgResolutionHours: (json['avgResolutionHours'] as num?)?.toDouble(),
    );
  }
}

class WardHeatmapPoint {
  final int wardId;
  final String wardName;
  final int complaintCount;
  final int openComplaintCount;

  WardHeatmapPoint({
    required this.wardId,
    required this.wardName,
    required this.complaintCount,
    required this.openComplaintCount,
  });

  factory WardHeatmapPoint.fromJson(Map<String, dynamic> json) {
    return WardHeatmapPoint(
      wardId: json['wardId'] as int,
      wardName: json['wardName'] as String,
      complaintCount: json['complaintCount'] as int,
      openComplaintCount: json['openComplaintCount'] as int,
    );
  }
}

class CategoryTrendPoint {
  final String category;
  final DateTime bucketDate;
  final int count;

  CategoryTrendPoint({required this.category, required this.bucketDate, required this.count});

  factory CategoryTrendPoint.fromJson(Map<String, dynamic> json) {
    return CategoryTrendPoint(
      category: json['category'] as String,
      bucketDate: DateTime.parse(json['bucketDate'] as String),
      count: json['count'] as int,
    );
  }
}

class DepartmentComparison {
  final int departmentId;
  final String departmentName;
  final int totalComplaints;
  final int openComplaints;
  final int resolvedComplaints;
  final double slaCompliancePercent;

  DepartmentComparison({
    required this.departmentId,
    required this.departmentName,
    required this.totalComplaints,
    required this.openComplaints,
    required this.resolvedComplaints,
    required this.slaCompliancePercent,
  });

  factory DepartmentComparison.fromJson(Map<String, dynamic> json) {
    return DepartmentComparison(
      departmentId: json['departmentId'] as int,
      departmentName: json['departmentName'] as String,
      totalComplaints: json['totalComplaints'] as int,
      openComplaints: json['openComplaints'] as int,
      resolvedComplaints: json['resolvedComplaints'] as int,
      slaCompliancePercent: (json['slaCompliancePercent'] as num).toDouble(),
    );
  }
}

class RoutingRuleEffectiveness {
  final int routingRuleId;
  final String category;
  final String departmentName;
  final int complaintCount;
  final double slaCompliancePercent;

  RoutingRuleEffectiveness({
    required this.routingRuleId,
    required this.category,
    required this.departmentName,
    required this.complaintCount,
    required this.slaCompliancePercent,
  });

  factory RoutingRuleEffectiveness.fromJson(Map<String, dynamic> json) {
    return RoutingRuleEffectiveness(
      routingRuleId: json['routingRuleId'] as int,
      category: json['category'] as String,
      departmentName: json['departmentName'] as String,
      complaintCount: json['complaintCount'] as int,
      slaCompliancePercent: (json['slaCompliancePercent'] as num).toDouble(),
    );
  }
}

/// GET /api/v1/dashboard/overview response (SRS 16.3 "Government
/// Dashboard (Overview)"). [dataAsOf] is SRS 15.10's Exceptions clause
/// made visible - see backend GovernmentDashboardResponse's Javadoc.
class GovernmentDashboardData {
  final KpiTiles kpis;
  final List<WardHeatmapPoint> heatmap;
  final List<CategoryTrendPoint> categoryTrend;
  final DateTime dataAsOf;
  final List<CategoryForecast> categoryForecast;

  GovernmentDashboardData({
    required this.kpis,
    required this.heatmap,
    required this.categoryTrend,
    required this.dataAsOf,
    this.categoryForecast = const [],
  });

  factory GovernmentDashboardData.fromJson(Map<String, dynamic> json) {
    return GovernmentDashboardData(
      kpis: KpiTiles.fromJson(json['kpis'] as Map<String, dynamic>),
      heatmap: (json['heatmap'] as List)
          .map((e) => WardHeatmapPoint.fromJson(e as Map<String, dynamic>))
          .toList(),
      categoryTrend: (json['categoryTrend'] as List)
          .map((e) => CategoryTrendPoint.fromJson(e as Map<String, dynamic>))
          .toList(),
      dataAsOf: DateTime.parse(json['dataAsOf'] as String),
      categoryForecast: ((json['categoryForecast'] as List?) ?? [])
          .map((e) => CategoryForecast.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }
}

/// GET /api/v1/dashboard/admin-summary response (SRS 24.3 "Admin
/// Dashboard").
class AdminDashboardSummary {
  final int activeUserCount;
  final int totalUserCount;
  final int totalComplaints;
  final int openComplaints;
  final int resolvedComplaints;
  final int aiAutoProcessedCount;
  final int manuallyProcessedCount;
  final double? aiAutoProcessingRatePercent;
  final int duplicateComplaintCount;
  final double duplicateMergeRatePercent;
  final List<RoutingRuleEffectiveness> routingRuleEffectiveness;
  final int configurationChangeCountLast30Days;
  final int notificationFailureCountLast30Days;
  final double notificationFailureRatePercent;
  final int currentlyEscalatedComplaints;
  final DateTime dataAsOf;

  AdminDashboardSummary({
    required this.activeUserCount,
    required this.totalUserCount,
    required this.totalComplaints,
    required this.openComplaints,
    required this.resolvedComplaints,
    required this.aiAutoProcessedCount,
    required this.manuallyProcessedCount,
    this.aiAutoProcessingRatePercent,
    required this.duplicateComplaintCount,
    required this.duplicateMergeRatePercent,
    required this.routingRuleEffectiveness,
    required this.configurationChangeCountLast30Days,
    required this.notificationFailureCountLast30Days,
    required this.notificationFailureRatePercent,
    required this.currentlyEscalatedComplaints,
    required this.dataAsOf,
  });

  factory AdminDashboardSummary.fromJson(Map<String, dynamic> json) {
    return AdminDashboardSummary(
      activeUserCount: json['activeUserCount'] as int,
      totalUserCount: json['totalUserCount'] as int,
      totalComplaints: json['totalComplaints'] as int,
      openComplaints: json['openComplaints'] as int,
      resolvedComplaints: json['resolvedComplaints'] as int,
      aiAutoProcessedCount: json['aiAutoProcessedCount'] as int,
      manuallyProcessedCount: json['manuallyProcessedCount'] as int,
      aiAutoProcessingRatePercent: (json['aiAutoProcessingRatePercent'] as num?)?.toDouble(),
      duplicateComplaintCount: json['duplicateComplaintCount'] as int,
      duplicateMergeRatePercent: (json['duplicateMergeRatePercent'] as num).toDouble(),
      routingRuleEffectiveness: (json['routingRuleEffectiveness'] as List)
          .map((e) => RoutingRuleEffectiveness.fromJson(e as Map<String, dynamic>))
          .toList(),
      configurationChangeCountLast30Days: json['configurationChangeCountLast30Days'] as int,
      notificationFailureCountLast30Days: json['notificationFailureCountLast30Days'] as int,
      notificationFailureRatePercent: (json['notificationFailureRatePercent'] as num).toDouble(),
      currentlyEscalatedComplaints: json['currentlyEscalatedComplaints'] as int,
      dataAsOf: DateTime.parse(json['dataAsOf'] as String),
    );
  }
}

/// Remaining-gaps item 10 (SRS 15.14 trend predictions): next-7-days outlook
/// for one category. Numeric fields are null when status is
/// INSUFFICIENT_HISTORY - the backend refuses to forecast rather than guess.
class CategoryForecast {
  final String category;
  final String status;
  final int weeksOfHistory;
  final int lastWeekCount;
  final double? trendPerWeek;
  final double? forecastNext7Days;
  final double? lower80;
  final double? upper80;

  CategoryForecast.fromJson(Map<String, dynamic> j)
      : category = j['category'] as String? ?? 'GENERAL',
        status = j['status'] as String? ?? 'INSUFFICIENT_HISTORY',
        weeksOfHistory = (j['weeksOfHistory'] as num?)?.toInt() ?? 0,
        lastWeekCount = (j['lastWeekCount'] as num?)?.toInt() ?? 0,
        trendPerWeek = (j['trendPerWeek'] as num?)?.toDouble(),
        forecastNext7Days = (j['forecastNext7Days'] as num?)?.toDouble(),
        lower80 = (j['lower80'] as num?)?.toDouble(),
        upper80 = (j['upper80'] as num?)?.toDouble();

  bool get hasForecast => status == 'FORECAST' && forecastNext7Days != null;
}
