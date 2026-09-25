import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/api/upload_file.dart';
import 'models/complaint.dart';
import 'models/complaint_status.dart';

/// Thin wrapper over ComplaintController's endpoints (backend, Phase 6;
/// Officer Module actions added Phase 12).
class ComplaintsApi {
  ComplaintsApi._();
  static final ComplaintsApi instance = ComplaintsApi._();

  final _client = ApiClient.instance;

  /// POST /api/v1/complaints (multipart). [latitude]/[longitude] are
  /// required by the backend (Location.latitude/longitude are NOT NULL -
  /// see LocationService's Javadoc for why there's no coordinate-free
  /// fallback). [wardId]/[locationSource] support the Gap-backlog Patch 9
  /// manual-location fallback (source=MANUAL_PIN) alongside the original
  /// device-GPS path (source=DEVICE_GPS, the default).
  Future<ComplaintDetail> submit({
    required UploadFile photo,
    String? description,
    // Remaining-gaps item 3: both null = ward-only fallback (wardId required server-side).
    double? latitude,
    double? longitude,
    int? wardId,
    String locationSource = 'DEVICE_GPS',
  }) async {
    final json = await _client.postMultipart(
      '/complaints',
      photo: photo,
      fields: {
        if (description != null && description.isNotEmpty) 'description': description,
        if (latitude != null) 'latitude': latitude.toString(),
        if (longitude != null) 'longitude': longitude.toString(),
        'locationSource': locationSource,
        if (wardId != null) 'wardId': wardId.toString(),
      },
    ) as Map<String, dynamic>;
    return ComplaintDetail.fromJson(json);
  }

  Future<ComplaintDetail> getDetail(int complaintId) async {
    final json = await _client.get('/complaints/$complaintId') as Map<String, dynamic>;
    return ComplaintDetail.fromJson(json);
  }

  /// GET /api/v1/complaints - citizen sees only their own complaints
  /// (ComplaintService.list scopes by role server-side; no citizenId
  /// param needed/accepted from the client).
  Future<List<ComplaintSummary>> list({ComplaintStatus? status, int page = 0, int pageSize = 20}) async {
    final json = await _client.get('/complaints', query: {
      if (status != null) 'status': status.wireName,
      'page': page,
      'pageSize': pageSize,
    }) as Map<String, dynamic>;
    final content = (json['content'] as List?) ?? [];
    return content.map((e) => ComplaintSummary.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// POST /api/v1/complaints/{id}/reopen (SRS Table 23) - only legal from
  /// Resolved/Closed and within the configured grace period; the backend
  /// returns 409 (surfaced as ApiException) once that window has passed.
  Future<ComplaintDetail> reopen(int complaintId, {String? reason}) async {
    final json = await _client.post('/complaints/$complaintId/reopen',
        body: (reason != null && reason.isNotEmpty) ? {'reason': reason} : null) as Map<String, dynamic>;
    return ComplaintDetail.fromJson(json);
  }

  /// POST .../confirm-resolution - Gap-backlog Patch 41: citizen confirms the fix (RESOLVED -> CLOSED).
  Future<ComplaintDetail> confirmResolution(int complaintId) async {
    final json = await _client.post('/complaints/$complaintId/confirm-resolution') as Map<String, dynamic>;
    return ComplaintDetail.fromJson(json);
  }

  /// PATCH .../approve-budget and .../reject-budget - Gap-backlog Patch 15.
  Future<void> approveBudget(int complaintId) async {
    await _client.patch('/complaints/$complaintId/approve-budget');
  }

  Future<void> rejectBudget(int complaintId, {String? note}) async {
    await _client.patch('/complaints/$complaintId/reject-budget',
        body: (note != null && note.isNotEmpty) ? {'note': note} : <String, dynamic>{});
  }

  /// GET /api/v1/complaints/appeals/pending - staff review queue (Gap-backlog Patch 12).
  Future<List<Map<String, dynamic>>> listPendingAppeals() async {
    final json = await _client.get('/complaints/appeals/pending') as List<dynamic>;
    return json.cast<Map<String, dynamic>>();
  }

  /// PATCH /api/v1/complaints/appeals/{appealId}/review (Gap-backlog Patch 12).
  Future<void> reviewAppeal(int appealId, {required String decision, String? note}) async {
    await _client.patch('/complaints/appeals/$appealId/review', body: {
      'decision': decision,
      if (note != null && note.isNotEmpty) 'note': note,
    });
  }

  /// PATCH /api/v1/complaints/{id}/verify (Gap-backlog Patch 25, Sep 2026
  /// audit) - the human-in-the-loop override for an AI_PROCESSING
  /// complaint. [decision] is 'VERIFIED', 'REJECTED', or 'DUPLICATE';
  /// [category] is required for VERIFIED, [rejectionReasonCode] for
  /// REJECTED, [parentComplaintId] for DUPLICATE (mirrors
  /// VerificationDecisionRequest's own field-requirements-depend-on-
  /// decision contract exactly - the backend still enforces this even if
  /// the Flutter form doesn't).
  Future<ComplaintDetail> verify(
    int complaintId, {
    required String decision,
    String? category,
    String? severity,
    String? rejectionReasonCode,
    int? parentComplaintId,
    String? note,
  }) async {
    final json = await _client.patch('/complaints/$complaintId/verify', body: {
      'decision': decision,
      if (category != null) 'category': category,
      if (severity != null) 'severity': severity,
      if (rejectionReasonCode != null) 'rejectionReasonCode': rejectionReasonCode,
      if (parentComplaintId != null) 'parentComplaintId': parentComplaintId,
      if (note != null && note.isNotEmpty) 'note': note,
    }) as Map<String, dynamic>;
    return ComplaintDetail.fromJson(json);
  }

  /// POST /api/v1/complaints/{id}/rating (Gap-backlog Patch 11, Sep 2026 audit).
  Future<void> rate(int complaintId, {required int rating, String? comment}) async {
    await _client.post('/complaints/$complaintId/rating', body: {
      'rating': rating,
      if (comment != null && comment.isNotEmpty) 'comment': comment,
    });
  }

  /// GET /api/v1/complaints/{id}/rating - returns null if not yet rated
  /// (backend responds 404, which ApiClient surfaces as an ApiException
  /// this method treats as "no rating yet" rather than propagating).
  Future<Map<String, dynamic>?> getRating(int complaintId) async {
    try {
      final json = await _client.get('/complaints/$complaintId/rating') as Map<String, dynamic>;
      return json;
    } on ApiException catch (e) {
      if (e.status == 404) return null;
      rethrow;
    }
  }

  /// POST /api/v1/complaints/{id}/appeal (Gap-backlog Patch 12, Sep 2026 audit).
  Future<void> appeal(int complaintId, {required String reason}) async {
    await _client.post('/complaints/$complaintId/appeal', body: {'reason': reason});
  }

  /// GET /api/v1/complaints/{id}/appeal - most recent appeal first.
  Future<List<Map<String, dynamic>>> listAppeals(int complaintId) async {
    final json = await _client.get('/complaints/$complaintId/appeal') as List<dynamic>;
    return json.cast<Map<String, dynamic>>();
  }

  /// PATCH /api/v1/complaints/{id}/status (SRS Table 23 + 17.3 "Officer
  /// Status Update Form"). [afterPhoto] is required by the backend only
  /// when [newStatus] is RESOLVED (SRS Table 8) - the screen itself
  /// enforces that before calling this, but the backend re-validates
  /// regardless (see ComplaintService.updateStatus's Javadoc) and returns
  /// a 400 ApiException if it's missing.
  Future<ComplaintDetail> updateStatus({
    required int complaintId,
    required ComplaintStatus newStatus,
    String? note,
    UploadFile? afterPhoto,
  }) async {
    final json = await _client.patchMultipart(
      '/complaints/$complaintId/status',
      fields: {
        'newStatus': newStatus.wireName,
        if (note != null && note.isNotEmpty) 'note': note,
      },
      file: afterPhoto,
    ) as Map<String, dynamic>;
    return ComplaintDetail.fromJson(json);
  }

  /// PATCH /api/v1/complaints/{id}/classification - Phase 12 (SRS 15.8
  /// "manual override by Officer/Department Head with justification").
  /// At least one of [category]/[severity] must be non-null; the backend
  /// rejects an empty override with a 400.
  Future<ComplaintDetail> overrideClassification({
    required int complaintId,
    String? category,
    String? severity,
    required String reason,
  }) async {
    final json = await _client.patch('/complaints/$complaintId/classification', body: {
      if (category != null) 'category': category,
      if (severity != null) 'severity': severity,
      'reason': reason,
    }) as Map<String, dynamic>;
    return ComplaintDetail.fromJson(json);
  }

  /// PATCH /api/v1/complaints/{id}/escalate - Phase 12 (SRS 16.2 Officer
  /// Queue "Escalate" button); a no-op on the backend if already
  /// escalated (see ComplaintService.escalate's Javadoc).
  Future<ComplaintDetail> escalate(int complaintId) async {
    final json = await _client.patch('/complaints/$complaintId/escalate') as Map<String, dynamic>;
    return ComplaintDetail.fromJson(json);
  }

  /// POST /api/v1/complaints/{id}/notes - Phase 12 (SRS 16.2 "Add
  /// Internal Note"), staff-only and never citizen-visible.
  Future<ComplaintDetail> addInternalNote({required int complaintId, required String note}) async {
    final json = await _client.post('/complaints/$complaintId/notes', body: {'note': note}) as Map<String, dynamic>;
    return ComplaintDetail.fromJson(json);
  }

  /// PATCH /api/v1/complaints/{id}/assign - Phase 11's manual
  /// reassignment endpoint (SRS 15.7 "manual reassignment by Admin or
  /// Department Head"), first given a Flutter caller in Phase 13
  /// (Department Head Module, SRS 16.2 "Reassign Officer"). Reused as-is,
  /// not duplicated - see ComplaintService.reassign's Javadoc for the
  /// Phase 13 department-scoping fix backing this call server-side:
  /// a DEPARTMENT_HEAD caller may only pass their own [departmentId] and
  /// only reassign a complaint already in their own department; the
  /// backend rejects anything else with a 403 regardless of what this
  /// screen offers.
  Future<ComplaintDetail> reassign({
    required int complaintId,
    required int departmentId,
    int? officerId,
    String? note,
  }) async {
    final json = await _client.patch('/complaints/$complaintId/assign', body: {
      'departmentId': departmentId,
      if (officerId != null) 'officerId': officerId,
      if (note != null && note.isNotEmpty) 'note': note,
    }) as Map<String, dynamic>;
    return ComplaintDetail.fromJson(json);
  }
}
