import '../../../core/api/api_time.dart';

/// Mirrors AuditLogResponse (backend, dto/admin/) - one row of the Admin
/// Audit Log screen (SRS 15.11 "audit log review").
class AuditLogEntry {
  final int logId;
  final int? actorId;
  final String actorName;
  final String actionType;
  final String entityType;
  final int entityId;
  final String? details;
  final DateTime? createdAt;

  AuditLogEntry({
    required this.logId,
    this.actorId,
    required this.actorName,
    required this.actionType,
    required this.entityType,
    required this.entityId,
    this.details,
    this.createdAt,
  });

  factory AuditLogEntry.fromJson(Map<String, dynamic> json) {
    return AuditLogEntry(
      logId: json['logId'] as int,
      actorId: json['actorId'] as int?,
      actorName: json['actorName'] as String,
      actionType: json['actionType'] as String,
      entityType: json['entityType'] as String,
      entityId: json['entityId'] as int,
      details: json['details'] as String?,
      createdAt: json['createdAt'] != null ? parseApiTimestamp(json['createdAt']) : null,
    );
  }
}
