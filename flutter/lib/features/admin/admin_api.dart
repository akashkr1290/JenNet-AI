import '../../core/api/api_client.dart';
import 'models/admin_routing_rule.dart';
import 'models/admin_user.dart';
import 'models/audit_log_entry.dart';
import 'models/platform_setting.dart';

/// Thin wrapper over Phase 14's four Admin controllers
/// (AdminUserController, AdminSettingsController, AdminRoutingRuleController's
/// new endpoints, AdminAuditLogController) - same "server enforces the
/// real scope, this is just a typed HTTP wrapper" convention as
/// DepartmentApi. Every method here is only ever called from
/// AdminHomeScreen's tabs, which are only ever reachable for an
/// ADMIN/SUPER_ADMIN session (see home_router.dart) - a non-admin caller
/// would just get a 403 from every one of these regardless.
class AdminApi {
  AdminApi._();
  static final AdminApi instance = AdminApi._();

  final _client = ApiClient.instance;

  // ---- Users (SRS 15.11 / 16.3 "User & Role Management") ----

  /// GET /api/v1/admin/users. Only the `content` list is consumed (same
  /// convention as ComplaintsApi.list) - no page-number/total-count UI
  /// exists this phase; a long result set is narrowed with [search]/
  /// [role]/[status]/[departmentId] instead of paged through.
  Future<List<AdminUser>> listUsers({
    String? role,
    String? status,
    int? departmentId,
    String? search,
    int page = 0,
    int pageSize = 50,
  }) async {
    final json = await _client.get('/admin/users', query: {
      if (role != null) 'role': role,
      if (status != null) 'status': status,
      if (departmentId != null) 'departmentId': departmentId,
      if (search != null && search.isNotEmpty) 'search': search,
      'page': page,
      'pageSize': pageSize,
    }) as Map<String, dynamic>;
    final content = (json['content'] as List?) ?? [];
    return content.map((e) => AdminUser.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<AdminCreateUserResult> createUser({
    required String fullName,
    required String mobileNumber,
    String? email,
    required String role,
    int? departmentId,
    int? wardId,
  }) async {
    final json = await _client.post('/admin/users', body: {
      'fullName': fullName,
      'mobileNumber': mobileNumber,
      if (email != null && email.isNotEmpty) 'email': email,
      'role': role,
      if (departmentId != null) 'departmentId': departmentId,
      if (wardId != null) 'wardId': wardId,
    }) as Map<String, dynamic>;
    return AdminCreateUserResult.fromJson(json);
  }

  Future<AdminUser> updateRole(int userId, String role) async {
    final json = await _client.patch('/admin/users/$userId/role', body: {'role': role}) as Map<String, dynamic>;
    return AdminUser.fromJson(json);
  }

  Future<AdminUser> updateStatus(int userId, String status) async {
    final json =
        await _client.patch('/admin/users/$userId/status', body: {'status': status}) as Map<String, dynamic>;
    return AdminUser.fromJson(json);
  }

  Future<void> resetPassword(int userId) async {
    await _client.post('/admin/users/$userId/reset-password');
  }

  Future<void> revokeSessions(int userId) async {
    await _client.post('/admin/users/$userId/revoke-sessions');
  }

  // ---- Settings (SRS 15.15 / 16.3 Admin Settings screen) ----

  Future<List<PlatformSetting>> listSettings() async {
    final json = await _client.get('/admin/settings') as List;
    return json.map((e) => PlatformSetting.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<PlatformSetting> updateSetting(String key, String value) async {
    final json = await _client.patch('/admin/settings/$key', body: {'value': value}) as Map<String, dynamic>;
    return PlatformSetting.fromJson(json);
  }

  // ---- Routing rules (SRS 17.4 / 16.3 Routing screen) ----

  Future<List<AdminRoutingRule>> listActiveRoutingRules() async {
    final json = await _client.get('/admin/routing-rules') as List;
    return json.map((e) => AdminRoutingRule.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<List<AdminRoutingRule>> routingRuleHistory() async {
    final json = await _client.get('/admin/routing-rules/history') as List;
    return json.map((e) => AdminRoutingRule.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<AdminRoutingRule> createRoutingRule({
    required String issueCategory,
    required int departmentId,
    required int slaHours,
    String? aiConfidenceThreshold,
    String? duplicateSimilarityThreshold,
  }) async {
    final json = await _client.post('/admin/routing-rules', body: {
      'issueCategory': issueCategory,
      'departmentId': departmentId,
      'slaHours': slaHours,
      if (aiConfidenceThreshold != null) 'aiConfidenceThreshold': aiConfidenceThreshold,
      if (duplicateSimilarityThreshold != null) 'duplicateSimilarityThreshold': duplicateSimilarityThreshold,
    }) as Map<String, dynamic>;
    return AdminRoutingRule.fromJson(json);
  }

  Future<AdminRoutingRule> deactivateRoutingRule(int routingRuleId) async {
    final json = await _client.patch('/admin/routing-rules/$routingRuleId/deactivate') as Map<String, dynamic>;
    return AdminRoutingRule.fromJson(json);
  }

  // ---- Audit log (SRS 15.11 "audit log review") ----

  Future<List<AuditLogEntry>> listAuditLogs({
    int? actorId,
    String? entityType,
    String? actionType,
    int page = 0,
    int pageSize = 50,
  }) async {
    final json = await _client.get('/admin/audit-logs', query: {
      if (actorId != null) 'actorId': actorId,
      if (entityType != null && entityType.isNotEmpty) 'entityType': entityType,
      if (actionType != null && actionType.isNotEmpty) 'actionType': actionType,
      'page': page,
      'pageSize': pageSize,
    }) as Map<String, dynamic>;
    final content = (json['content'] as List?) ?? [];
    return content.map((e) => AuditLogEntry.fromJson(e as Map<String, dynamic>)).toList();
  }
}
