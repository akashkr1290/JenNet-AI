import '../../core/api/api_client.dart';
import 'models/department_performance.dart';

/// Simple department reference (mirrors DepartmentResponse, backend,
/// dto/department/) - kept minimal since the only current consumer
/// (AdminUserManagementScreen / AdminRoutingRulesScreen department
/// picker dropdowns, Phase 14) only needs id + name.
class DepartmentOption {
  final int departmentId;
  final String name;
  final bool isActive;

  DepartmentOption({required this.departmentId, required this.name, required this.isActive});

  factory DepartmentOption.fromJson(Map<String, dynamic> json) {
    return DepartmentOption(
      departmentId: json['departmentId'] as int,
      name: json['name'] as String,
      isActive: json['isActive'] as bool? ?? true,
    );
  }
}

/// Thin wrapper over DepartmentController's Phase 13 additions (backend).
/// Every call here passes a [departmentId] the caller believes is their
/// own - the backend re-derives and enforces the real scope regardless
/// (DepartmentPerformanceService.requireScopedDepartmentId), so a bug in
/// which [departmentId] this screen sends can only ever result in a 403,
/// never in seeing another department's data. See UserApi for how a
/// DEPARTMENT_HEAD screen learns its own departmentId in the first place
/// (not present in the JWT payload).
class DepartmentApi {
  DepartmentApi._();
  static final DepartmentApi instance = DepartmentApi._();

  final _client = ApiClient.instance;

  /// GET /api/v1/departments - Phase 14 addition: the department picker
  /// dropdown backing the Admin "Add User" and "Add Routing Rule" forms
  /// (AdminUserManagementScreen / AdminRoutingRulesScreen). The endpoint
  /// itself pre-dates this phase (Phase 11) but no Flutter screen had
  /// needed the full list until now.
  Future<List<DepartmentOption>> list() async {
    final json = await _client.get('/departments') as List;
    return json.map((e) => DepartmentOption.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// GET /api/v1/departments/{id}/officers - the officer picker backing
  /// "Reassign Officer" (SRS 16.2).
  Future<List<OfficerSummary>> officers(int departmentId) async {
    final json = await _client.get('/departments/$departmentId/officers') as List;
    return json.map((e) => OfficerSummary.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// GET /api/v1/departments/{id}/performance - KPI tiles, SLA
  /// compliance, officer workload table (SRS 16.2 "Department
  /// Performance View").
  Future<DepartmentPerformance> performance(int departmentId) async {
    final json = await _client.get('/departments/$departmentId/performance') as Map<String, dynamic>;
    return DepartmentPerformance.fromJson(json);
  }

  /// GET /api/v1/departments/{id}/performance/export - the "Export
  /// Report" button (SRS 16.2 + SRS 21 "Reports can be exported as PDF
  /// or CSV" - CSV only this phase, see
  /// DepartmentPerformanceService.exportPerformanceCsv's Javadoc for
  /// why). Uses [ApiClient.getRaw] rather than [ApiClient.get] - the
  /// response is `text/csv`, not JSON.
  Future<String> exportPerformanceCsv(int departmentId) async {
    return _client.getRaw('/departments/$departmentId/performance/export');
  }
}
