import 'complaint_status.dart';

/// Mirrors LocationResponse - Phase 12 addition (Officer Queue/Detail
/// needs the address/coordinates to actually locate the issue; the
/// citizen-only Phase 6 screens never needed to render this).
class ComplaintLocation {
  final double? latitude;
  final double? longitude;
  final String? wardName;
  final String? formattedAddress;

  ComplaintLocation({this.latitude, this.longitude, this.wardName, this.formattedAddress});

  factory ComplaintLocation.fromJson(Map<String, dynamic> json) {
    return ComplaintLocation(
      latitude: (json['latitude'] as num?)?.toDouble(),
      longitude: (json['longitude'] as num?)?.toDouble(),
      wardName: json['wardName'] as String?,
      formattedAddress: json['formattedAddress'] as String?,
    );
  }
}

/// Mirrors InternalNoteResponse - Phase 12 (SRS 16.2 "Add Internal
/// Note"), staff-only (see ComplaintService.toResponse's Javadoc for the
/// citizen-filtering rule this list already respects server-side).
class InternalNote {
  final int logId;
  final String? authorName;
  final String note;
  final DateTime? createdAt;

  InternalNote({required this.logId, this.authorName, required this.note, this.createdAt});

  factory InternalNote.fromJson(Map<String, dynamic> json) {
    return InternalNote(
      logId: json['logId'] as int,
      authorName: json['authorName'] as String?,
      note: json['note'] as String? ?? '',
      createdAt: json['createdAt'] != null ? DateTime.tryParse(json['createdAt'] as String) : null,
    );
  }
}

/// Mirrors ComplaintSummaryResponse (backend, dto/complaint/) - the list/
/// tracking view shape.
class ComplaintSummary {
  final int complaintId;
  final String referenceNumber;
  final String category;
  final String? description;
  final ComplaintStatus status;
  final String? severity;
  final int corroborationCount;
  final bool isEscalated;
  final bool isReopened;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  ComplaintSummary({
    required this.complaintId,
    required this.referenceNumber,
    required this.category,
    this.description,
    required this.status,
    this.severity,
    required this.corroborationCount,
    required this.isEscalated,
    required this.isReopened,
    this.createdAt,
    this.updatedAt,
  });

  factory ComplaintSummary.fromJson(Map<String, dynamic> json) {
    return ComplaintSummary(
      complaintId: json['complaintId'] as int,
      referenceNumber: json['referenceNumber'] as String,
      category: json['category'] as String,
      description: json['description'] as String?,
      status: ComplaintStatus.fromJson(json['status'] as String),
      severity: json['severity'] as String?,
      corroborationCount: (json['corroborationCount'] as int?) ?? 1,
      isEscalated: (json['isEscalated'] as bool?) ?? false,
      isReopened: (json['isReopened'] as bool?) ?? false,
      createdAt: json['createdAt'] != null ? DateTime.tryParse(json['createdAt'] as String) : null,
      updatedAt: json['updatedAt'] != null ? DateTime.tryParse(json['updatedAt'] as String) : null,
    );
  }
}

/// Mirrors ImageResponse - deliberately no storage key/URL (media storage
/// contract - see backend ImageResponse's Javadoc); Flutter can't render
/// the photo itself yet without a real pre-signed-URL-issuing storage
/// backend, only its metadata.
/// Mirrors ImageResponse. Gap-backlog Patch 6 (Sep 2026 audit): viewUrl is
/// now a real, short-lived, presigned/signed URL the backend resolves
/// per-request (see backend ImageResponse's Javadoc) - Flutter can render
/// the actual photo now, not just its metadata.
class ComplaintImage {
  final int imageId;
  final String imageType;
  final String contentType;
  final int? fileSizeBytes;
  final DateTime? uploadedAt;
  final String? viewUrl;

  ComplaintImage({
    required this.imageId,
    required this.imageType,
    required this.contentType,
    this.fileSizeBytes,
    this.uploadedAt,
    this.viewUrl,
  });

  factory ComplaintImage.fromJson(Map<String, dynamic> json) {
    return ComplaintImage(
      imageId: json['imageId'] as int,
      imageType: json['imageType'] as String,
      contentType: json['contentType'] as String,
      fileSizeBytes: json['fileSizeBytes'] as int?,
      uploadedAt: json['uploadedAt'] != null ? DateTime.tryParse(json['uploadedAt'] as String) : null,
      viewUrl: json['viewUrl'] as String?,
    );
  }
}

/// Mirrors BudgetResponse - Gap-backlog Patch 15 (Sep 2026 audit).
class ComplaintBudget {
  final double? estimatedCostMin;
  final double? estimatedCostMax;
  final int? estimatedResolutionDays;
  final String? confidenceLevel;
  final bool approvalRequired;
  final bool approved;
  final String? approvedByName;
  final String approvalStatus;
  final DateTime? approvedAt;

  ComplaintBudget({
    this.estimatedCostMin,
    this.estimatedCostMax,
    this.estimatedResolutionDays,
    this.confidenceLevel,
    required this.approvalRequired,
    required this.approved,
    this.approvedByName,
    this.approvalStatus = 'PENDING',
    this.approvedAt,
  });

  factory ComplaintBudget.fromJson(Map<String, dynamic> json) {
    return ComplaintBudget(
      estimatedCostMin: (json['estimatedCostMin'] as num?)?.toDouble(),
      estimatedCostMax: (json['estimatedCostMax'] as num?)?.toDouble(),
      estimatedResolutionDays: json['estimatedResolutionDays'] as int?,
      confidenceLevel: json['confidenceLevel'] as String?,
      approvalRequired: (json['approvalRequired'] as bool?) ?? false,
      approved: (json['approved'] as bool?) ?? false,
      approvedByName: json['approvedByName'] as String?,
      approvalStatus: json['approvalStatus'] as String? ?? 'PENDING',
      approvedAt: json['approvedAt'] != null ? DateTime.tryParse(json['approvedAt'] as String) : null,
    );
  }
}

/// Mirrors AiClassificationResponse - Gap-backlog Patch 30/43 (Sep 2026 audit).
class AiClassification {
  final double? confidence;
  final String? modelVersion;
  final bool duplicateFlagged;
  final String aiStatus;
  final List<DetectedBox> detections;

  AiClassification({
    this.confidence,
    this.modelVersion,
    required this.duplicateFlagged,
    required this.aiStatus,
    this.detections = const [],
  });

  factory AiClassification.fromJson(Map<String, dynamic> json) {
    return AiClassification(
      confidence: (json['confidence'] as num?)?.toDouble(),
      modelVersion: json['modelVersion'] as String?,
      duplicateFlagged: (json['duplicateFlagged'] as bool?) ?? false,
      aiStatus: json['aiStatus'] as String? ?? 'MODEL_UNAVAILABLE',
      detections: ((json['detections'] as List?) ?? [])
          .map((e) => DetectedBox.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }
}

/// Gap-backlog Patch 42: one AI detection, coordinates normalised 0..1 of the original photo.
class DetectedBox {
  final String className;
  final double confidence;
  final double x1, y1, x2, y2;

  DetectedBox.fromJson(Map<String, dynamic> j)
      : className = j['className'] as String? ?? '',
        confidence = (j['confidence'] as num?)?.toDouble() ?? 0,
        x1 = (j['x1'] as num?)?.toDouble() ?? 0,
        y1 = (j['y1'] as num?)?.toDouble() ?? 0,
        x2 = (j['x2'] as num?)?.toDouble() ?? 0,
        y2 = (j['y2'] as num?)?.toDouble() ?? 0;
}

/// Mirrors StatusHistoryResponse.
class StatusHistoryEntry {
  final String? previousStatus;
  final String newStatus;
  final String actorType;
  final String? actorName;
  final String? reason;
  final DateTime? changedAt;

  StatusHistoryEntry({
    this.previousStatus,
    required this.newStatus,
    required this.actorType,
    this.actorName,
    this.reason,
    this.changedAt,
  });

  factory StatusHistoryEntry.fromJson(Map<String, dynamic> json) {
    return StatusHistoryEntry(
      previousStatus: json['previousStatus'] as String?,
      newStatus: json['newStatus'] as String,
      actorType: json['actorType'] as String,
      actorName: json['actorName'] as String?,
      reason: json['reason'] as String?,
      changedAt: json['changedAt'] != null ? DateTime.tryParse(json['changedAt'] as String) : null,
    );
  }
}

/// Mirrors ComplaintResponse - the full detail view.
class ComplaintDetail {
  final int complaintId;
  final String referenceNumber;
  final String category;
  final String? description;
  final ComplaintStatus status;
  final String? severity;
  final int corroborationCount;
  final bool isEscalated;
  final bool isReopened;
  final String? rejectionReasonCode;
  final int? departmentId;
  final int? assignedOfficerId;
  final ComplaintLocation? location;
  final List<ComplaintImage> images;
  final List<StatusHistoryEntry> statusHistory;
  final List<InternalNote> internalNotes;
  final ComplaintBudget? budget;
  final AiClassification? aiClassification;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  ComplaintDetail({
    required this.complaintId,
    required this.referenceNumber,
    required this.category,
    this.description,
    required this.status,
    this.severity,
    required this.corroborationCount,
    required this.isEscalated,
    required this.isReopened,
    this.rejectionReasonCode,
    this.departmentId,
    this.assignedOfficerId,
    this.location,
    required this.images,
    required this.statusHistory,
    this.internalNotes = const [],
    this.budget,
    this.aiClassification,
    this.createdAt,
    this.updatedAt,
  });

  factory ComplaintDetail.fromJson(Map<String, dynamic> json) {
    return ComplaintDetail(
      complaintId: json['complaintId'] as int,
      referenceNumber: json['referenceNumber'] as String,
      category: json['category'] as String,
      description: json['description'] as String?,
      status: ComplaintStatus.fromJson(json['status'] as String),
      severity: json['severity'] as String?,
      corroborationCount: (json['corroborationCount'] as int?) ?? 1,
      isEscalated: (json['isEscalated'] as bool?) ?? false,
      isReopened: (json['isReopened'] as bool?) ?? false,
      rejectionReasonCode: json['rejectionReasonCode'] as String?,
      departmentId: json['departmentId'] as int?,
      assignedOfficerId: json['assignedOfficerId'] as int?,
      location: json['location'] != null
          ? ComplaintLocation.fromJson(json['location'] as Map<String, dynamic>)
          : null,
      images: ((json['images'] as List?) ?? [])
          .map((e) => ComplaintImage.fromJson(e as Map<String, dynamic>))
          .toList(),
      statusHistory: ((json['statusHistory'] as List?) ?? [])
          .map((e) => StatusHistoryEntry.fromJson(e as Map<String, dynamic>))
          .toList(),
      internalNotes: ((json['internalNotes'] as List?) ?? [])
          .map((e) => InternalNote.fromJson(e as Map<String, dynamic>))
          .toList(),
      budget: json['budget'] != null ? ComplaintBudget.fromJson(json['budget'] as Map<String, dynamic>) : null,
      aiClassification: json['aiClassification'] != null
          ? AiClassification.fromJson(json['aiClassification'] as Map<String, dynamic>)
          : null,
      createdAt: json['createdAt'] != null ? DateTime.tryParse(json['createdAt'] as String) : null,
      updatedAt: json['updatedAt'] != null ? DateTime.tryParse(json['updatedAt'] as String) : null,
    );
  }
}
