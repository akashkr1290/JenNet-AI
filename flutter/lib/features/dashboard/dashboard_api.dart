import '../../core/api/api_client.dart';
import 'models/dashboard_models.dart';

/// Thin wrapper over GovernmentDashboardController (backend, Phase 16).
/// Every method's [departmentId] follows the same convention as the
/// backend endpoint it calls: `null` means "my own department" for a
/// DEPARTMENT_HEAD caller, or "jurisdiction-wide" for an ADMIN/
/// SUPER_ADMIN caller (see GovernmentDashboardService's Javadoc) - the
/// backend re-derives and enforces the real scope regardless, same
/// "a Flutter-side mistake can only ever produce a 403, never leak
/// another department's data" precedent as DepartmentApi (Phase 13).
class DashboardApi {
  DashboardApi._();
  static final DashboardApi instance = DashboardApi._();

  final _client = ApiClient.instance;

  /// GET /api/v1/dashboard/overview - KPI tiles, heatmap, category trend
  /// (SRS 16.3 "Government Dashboard (Overview)").
  Future<GovernmentDashboardData> overview({int? departmentId}) async {
    final json = await _client.get(
      '/dashboard/overview',
      query: departmentId != null ? {'departmentId': departmentId} : null,
    ) as Map<String, dynamic>;
    return GovernmentDashboardData.fromJson(json);
  }

  /// GET /api/v1/dashboard/department-comparison - ADMIN/SUPER_ADMIN
  /// only (SRS 15.10/24.3/24.4 "department comparison chart").
  Future<List<DepartmentComparison>> departmentComparison() async {
    final json = await _client.get('/dashboard/department-comparison') as List;
    return json.map((e) => DepartmentComparison.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// GET /api/v1/dashboard/admin-summary - ADMIN/SUPER_ADMIN only (SRS
  /// 24.3 "Admin Dashboard").
  Future<AdminDashboardSummary> adminSummary() async {
    final json = await _client.get('/dashboard/admin-summary') as Map<String, dynamic>;
    return AdminDashboardSummary.fromJson(json);
  }

  /// GET /api/v1/dashboard/export - the dashboard's "Export" button (SRS
  /// 16.3 Buttons: "Export"). Same clipboard-copy approach as Phase 13's
  /// DepartmentApi.exportPerformanceCsv (no file-save/share plugin
  /// available in this environment - see that method's Javadoc for the
  /// full reasoning, unchanged here).
  Future<String> exportCsv({int? departmentId}) async {
    return _client.getRaw(
      '/dashboard/export',
      query: departmentId != null ? {'departmentId': departmentId} : null,
    );
  }
}
