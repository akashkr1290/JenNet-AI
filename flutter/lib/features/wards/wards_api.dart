import '../../core/api/api_client.dart';
import 'models/ward.dart';

/// Wraps WardController's authenticated `/api/v1/wards` (used post-login,
/// e.g. the GPS-fallback manual-location picker and the profile screen) and
/// PublicWardController's unauthenticated `/api/v1/public/wards` (the
/// registration screen's ward picker, before any JWT exists). Both return
/// the same public-safe WardResponse shape (id, name, code).
class WardsApi {
  WardsApi._();
  static final WardsApi instance = WardsApi._();

  final _client = ApiClient.instance;

  Future<List<Ward>> listActiveWards() async {
    final json = await _client.get('/wards') as List<dynamic>;
    return json.map((e) => Ward.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// GET /api/v1/public/wards - permitAll in SecurityConfig (Gap-backlog
  /// Patch 8), so it works on the registration screen before sign-in.
  Future<List<Ward>> listPublicWards() async {
    final json = await _client.get('/public/wards') as List<dynamic>;
    return json.map((e) => Ward.fromJson(e as Map<String, dynamic>)).toList();
  }
}
