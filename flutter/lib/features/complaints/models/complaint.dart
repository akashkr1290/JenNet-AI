import 'complaint_status.dart';
import '../../../core/api/api_time.dart';
import '../../../core/api/api_config.dart';

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
      createdAt: json['createdAt'] != null ? parseApiTimestamp(json['createdAt']) : null,
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

  /// Audit GAP-040: persisted SLA deadline (backend complaints.sla_due_at); null when no clock runs.
  final DateTime? slaDueAt;

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
    this.slaDueAt,
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
      createdAt: json['createdAt'] != null ? parseApiTimestamp(json['createdAt']) : null,
      updatedAt: json['updatedAt'] != null ? parseApiTimestamp(json['updatedAt']) : null,
      slaDueAt: json['slaDueAt'] != null ? parseApiTimestamp(json['slaDueAt']) : null,
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
      uploadedAt: json['uploadedAt'] != null ? parseApiTimestamp(json['uploadedAt']) : null,
      // Host-relative from the backend (local storage) - see ApiConfig.resolve.
      viewUrl: json['viewUrl'] == null ? null : ApiConfig.resolve(json['viewUrl'] as String),
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
      approvedAt: json['approvedAt'] != null ? parseApiTimestamp(json['approvedAt']) : null,
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

  /// Which stage produced the category (YOLO -> Gemini -> manual review);
  /// null for complaints classified before this was recorded.
  final AiDecision? decision;

  /// The model ran but recognised nothing (the service reports 0, not a real
  /// score) - shown as "not recognised" rather than "0% confidence".
  bool get nothingRecognised => confidence != null && confidence! <= 0;

  AiClassification({
    this.confidence,
    this.modelVersion,
    required this.duplicateFlagged,
    required this.aiStatus,
    this.detections = const [],
    this.decision,
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
      decision: json['decision'] == null ? null : AiDecision.fromJson(json['decision'] as Map<String, dynamic>),
    );
  }
}

/// State of one step in the AI decision path.
enum AiStepState { passed, failed, skipped }

/// One line of the AI decision path, e.g. "YOLO detection" / "pothole 75%".
class AiStep {
  final String title;
  final String detail;
  final AiStepState state;
  const AiStep(this.title, this.detail, this.state);
}

/// Mirrors AiClassificationResponse.AiDecision (pilot request 2026-09-28):
/// YOLO first; if YOLO is below its threshold Gemini verifies; if both fail
/// the complaint goes to manual review.
class AiDecision {
  final String outcome;
  final double yoloThreshold;
  final bool yoloPassed;
  final String? yoloClass;
  final double? yoloConfidence;
  final bool geminiUsed;
  final String? geminiCategory;
  final bool? geminiAgrees;
  final String? geminiUnavailableReason;
  final double autoApproveThreshold;
  final bool autoApproved;

  const AiDecision({
    required this.outcome,
    required this.yoloThreshold,
    required this.yoloPassed,
    this.yoloClass,
    this.yoloConfidence,
    required this.geminiUsed,
    this.geminiCategory,
    this.geminiAgrees,
    this.geminiUnavailableReason,
    required this.autoApproveThreshold,
    required this.autoApproved,
  });

  factory AiDecision.fromJson(Map<String, dynamic> j) => AiDecision(
        outcome: j['outcome'] as String? ?? 'MANUAL_REVIEW',
        yoloThreshold: (j['yoloThreshold'] as num?)?.toDouble() ?? 50,
        yoloPassed: (j['yoloPassed'] as bool?) ?? false,
        yoloClass: j['yoloClass'] as String?,
        yoloConfidence: (j['yoloConfidence'] as num?)?.toDouble(),
        geminiUsed: (j['geminiUsed'] as bool?) ?? false,
        geminiCategory: j['geminiCategory'] as String?,
        geminiAgrees: j['geminiAgrees'] as bool?,
        geminiUnavailableReason: j['geminiUnavailableReason'] as String?,
        autoApproveThreshold: (j['autoApproveThreshold'] as num?)?.toDouble() ?? 50,
        autoApproved: (j['autoApproved'] as bool?) ?? false,
      );

  static String _label(String? s) => (s ?? '').replaceAll('_', ' ').toLowerCase();
  static String _pct(double v) => '${v.toStringAsFixed(0)}%';

  /// True when a person has to confirm the category.
  bool get needsManualReview => !autoApproved;

  AiStep get yoloStep {
    final threshold = _pct(yoloThreshold);
    if (outcome == 'MODEL_UNAVAILABLE') {
      return const AiStep('YOLO detection', 'Detection model unavailable', AiStepState.failed);
    }
    if (yoloPassed && yoloClass != null && yoloConfidence != null) {
      return AiStep('YOLO detection',
          '${_label(yoloClass)} ${_pct(yoloConfidence!)} (threshold $threshold) - passed', AiStepState.passed);
    }
    if (yoloClass != null && yoloConfidence != null) {
      return AiStep('YOLO detection',
          'Best guess ${_label(yoloClass)} ${_pct(yoloConfidence!)} - below the $threshold threshold',
          AiStepState.failed);
    }
    return AiStep('YOLO detection', 'Nothing detected (threshold $threshold)', AiStepState.failed);
  }

  AiStep get geminiStep {
    const title = 'Gemini verification';
    if (!geminiUsed) {
      final why = geminiUnavailableReason == null ? '' : ' ($geminiUnavailableReason)';
      return AiStep(title, 'Not available$why', AiStepState.skipped);
    }
    final named = geminiCategory != null && geminiCategory != 'GENERAL';
    switch (outcome) {
      case 'YOLO_CONFIRMED_BY_GEMINI':
        return AiStep(title, 'Agrees${named ? ': ${_label(geminiCategory)}' : ''}', AiStepState.passed);
      case 'GEMINI_REVISED':
        return AiStep(title, 'Disagrees - suggests ${_label(geminiCategory)}', AiStepState.passed);
      case 'YOLO_GEMINI_DISAGREED':
        return const AiStep(title, 'Disagrees with YOLO', AiStepState.failed);
      case 'GEMINI_VERIFIED':
        return AiStep(title, 'Identified ${_label(geminiCategory)}', AiStepState.passed);
      default:
        return AiStep(title, named ? 'Suggests ${_label(geminiCategory)}' : 'Could not identify the issue',
            named ? AiStepState.passed : AiStepState.failed);
    }
  }

  /// Pilot workflow 2026-09-30: at or above the AI confidence threshold the
  /// classification is accepted and the complaint goes to its department's
  /// Department Head; below it the Verification Team checks the category.
  AiStep get resultStep {
    const title = 'Result';
    if (autoApproved) {
      return AiStep(title,
          'Accepted automatically (confidence at least ${_pct(autoApproveThreshold)}) - sent to the department',
          AiStepState.passed);
    }
    switch (outcome) {
      case 'YOLO_CONFIRMED_BY_GEMINI':
      case 'YOLO_ONLY':
        return AiStep(title,
            'Detected by YOLO - below the ${_pct(autoApproveThreshold)} threshold, the Verification Team will confirm',
            AiStepState.passed);
      case 'GEMINI_VERIFIED':
        return const AiStep(title, 'Verified by Gemini - the Verification Team will confirm', AiStepState.passed);
      case 'GEMINI_REVISED':
        return const AiStep(title, 'Category revised by Gemini - the Verification Team will confirm', AiStepState.passed);
      case 'OCR_HINT':
        return const AiStep(title, 'Suggested from text in the photo - the Verification Team will confirm',
            AiStepState.passed);
      case 'YOLO_GEMINI_DISAGREED':
        return const AiStep(title, 'YOLO and Gemini disagree - Verification Team review', AiStepState.failed);
      case 'MODEL_UNAVAILABLE':
        return const AiStep(title, 'AI unavailable - Verification Team review', AiStepState.failed);
      default:
        return const AiStep(title, 'YOLO and Gemini could not identify it - Verification Team review',
            AiStepState.failed);
    }
  }

  List<AiStep> get steps => [yoloStep, geminiStep, resultStep];
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
      changedAt: json['changedAt'] != null ? parseApiTimestamp(json['changedAt']) : null,
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

  /// Pilot workflow 2026-09-30: who the complaint is with.
  final String? departmentName;
  final String? assignedOfficerName;

  /// When a Resolved complaint closes automatically if the citizen neither
  /// confirms nor reopens it; null for every other status.
  final DateTime? autoCloseAt;

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
    this.departmentName,
    this.assignedOfficerName,
    this.autoCloseAt,
  });

  /// Routed to a department (or legacy department-level ASSIGNED) but no
  /// Government Officer yet - the Department Head has to assign one.
  bool get awaitingOfficer =>
      assignedOfficerId == null &&
      departmentId != null &&
      (status == ComplaintStatus.verified || status == ComplaintStatus.assigned);

  /// The Government Officer's resolution note: the reason recorded on the
  /// latest move to Resolved (SRS Table 8 - mandatory, min 10 characters).
  StatusHistoryEntry? get resolutionEntry {
    for (final entry in statusHistory.reversed) {
      if (entry.newStatus == ComplaintStatus.resolved.wireName) return entry;
    }
    return null;
  }

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
      createdAt: json['createdAt'] != null ? parseApiTimestamp(json['createdAt']) : null,
      updatedAt: json['updatedAt'] != null ? parseApiTimestamp(json['updatedAt']) : null,
      departmentName: json['departmentName'] as String?,
      assignedOfficerName: json['assignedOfficerName'] as String?,
      autoCloseAt: json['autoCloseAt'] != null ? parseApiTimestamp(json['autoCloseAt']) : null,
    );
  }
}
