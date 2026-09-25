import '../../../core/api/api_time.dart';

/// Mirrors RoutingRuleResponse (backend, dto/department/) - category kept
/// as a plain String (same convention as ComplaintSummary.category in
/// features/complaints/models/complaint.dart), not a Dart enum.
class AdminRoutingRule {
  final int routingRuleId;
  final String issueCategory;
  final int departmentId;
  final String departmentName;
  final String aiConfidenceThreshold;
  final String duplicateSimilarityThreshold;
  final int slaHours;
  final String effectiveFrom;
  final bool isActive;
  final DateTime? createdAt;

  AdminRoutingRule({
    required this.routingRuleId,
    required this.issueCategory,
    required this.departmentId,
    required this.departmentName,
    required this.aiConfidenceThreshold,
    required this.duplicateSimilarityThreshold,
    required this.slaHours,
    required this.effectiveFrom,
    required this.isActive,
    this.createdAt,
  });

  factory AdminRoutingRule.fromJson(Map<String, dynamic> json) {
    return AdminRoutingRule(
      routingRuleId: json['routingRuleId'] as int,
      issueCategory: json['issueCategory'] as String,
      departmentId: json['departmentId'] as int,
      departmentName: json['departmentName'] as String,
      aiConfidenceThreshold: json['aiConfidenceThreshold'].toString(),
      duplicateSimilarityThreshold: json['duplicateSimilarityThreshold'].toString(),
      slaHours: json['slaHours'] as int,
      effectiveFrom: json['effectiveFrom'] as String,
      isActive: json['isActive'] as bool,
      createdAt: json['createdAt'] != null ? parseApiTimestamp(json['createdAt']) : null,
    );
  }
}

/// Matches entity.enums.ComplaintCategory exactly (V14's CHECK constraint) -
/// the "Add Routing Rule" form's category dropdown source.
const List<String> issueCategories = [
  'POTHOLE',
  'GARBAGE_OVERFLOW',
  'WATER_LEAKAGE',
  'BROKEN_STREET_LIGHT',
  'OPEN_MANHOLE',
  'ILLEGAL_CONSTRUCTION',
  'GENERAL',
];
