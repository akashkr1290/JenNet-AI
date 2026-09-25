import '../../../core/api/api_time.dart';

/// Audit GAP-020 / GAP-031 / GAP-033: models for the Admin "Setup" screens
/// (departments, wards, sensitive zones, out-of-jurisdiction review).

class AdminDepartment {
  final int departmentId;
  final String name;
  final String? description;
  final int? headUserId;
  final bool isActive;

  AdminDepartment({required this.departmentId, required this.name, this.description, this.headUserId, required this.isActive});

  factory AdminDepartment.fromJson(Map<String, dynamic> json) => AdminDepartment(
        departmentId: (json['departmentId'] as num).toInt(),
        name: json['name'] as String,
        description: json['description'] as String?,
        headUserId: (json['headUserId'] as num?)?.toInt(),
        isActive: (json['isActive'] as bool?) ?? true,
      );
}

class AdminWard {
  final int wardId;
  final String name;
  final String? code;
  final String? boundaryGeojson;
  final bool hasBoundary;
  final bool isActive;

  AdminWard({
    required this.wardId,
    required this.name,
    this.code,
    this.boundaryGeojson,
    required this.hasBoundary,
    required this.isActive,
  });

  factory AdminWard.fromJson(Map<String, dynamic> json) => AdminWard(
        wardId: (json['wardId'] as num).toInt(),
        name: json['name'] as String,
        code: json['code'] as String?,
        boundaryGeojson: json['boundaryGeojson'] as String?,
        hasBoundary: (json['hasBoundary'] as bool?) ?? false,
        isActive: (json['isActive'] as bool?) ?? true,
      );
}

const Map<String, String> sensitiveZoneTypeLabels = {
  'SCHOOL': 'School',
  'HOSPITAL': 'Hospital',
  'HIGH_TRAFFIC_ROAD': 'High-traffic road',
};

class SensitiveZone {
  final int zoneId;
  final String name;
  final String zoneType;
  final double latitude;
  final double longitude;
  final int radiusMeters;
  final bool isActive;

  SensitiveZone({
    required this.zoneId,
    required this.name,
    required this.zoneType,
    required this.latitude,
    required this.longitude,
    required this.radiusMeters,
    required this.isActive,
  });

  factory SensitiveZone.fromJson(Map<String, dynamic> json) => SensitiveZone(
        zoneId: (json['zoneId'] as num).toInt(),
        name: json['name'] as String,
        zoneType: json['zoneType'] as String,
        latitude: (json['latitude'] as num).toDouble(),
        longitude: (json['longitude'] as num).toDouble(),
        radiusMeters: (json['radiusMeters'] as num).toInt(),
        isActive: (json['isActive'] as bool?) ?? true,
      );
}

class OutOfJurisdictionItem {
  final int complaintId;
  final String referenceNumber;
  final String status;
  final String? category;
  final double latitude;
  final double longitude;
  final String? locationSource;
  final String? wardName;
  final DateTime? createdAt;

  OutOfJurisdictionItem({
    required this.complaintId,
    required this.referenceNumber,
    required this.status,
    this.category,
    required this.latitude,
    required this.longitude,
    this.locationSource,
    this.wardName,
    this.createdAt,
  });

  factory OutOfJurisdictionItem.fromJson(Map<String, dynamic> json) => OutOfJurisdictionItem(
        complaintId: (json['complaintId'] as num).toInt(),
        referenceNumber: json['referenceNumber'] as String,
        status: json['status'] as String,
        category: json['category'] as String?,
        latitude: (json['latitude'] as num).toDouble(),
        longitude: (json['longitude'] as num).toDouble(),
        locationSource: json['locationSource'] as String?,
        wardName: json['wardName'] as String?,
        createdAt: json['createdAt'] != null ? parseApiTimestamp(json['createdAt']) : null,
      );
}
