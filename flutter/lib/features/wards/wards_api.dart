import '../../core/api/api_client.dart';
import 'models/ward.dart';

/// Wraps WardController's authenticated `/api/v1/wards` (used post-login,
/// e.g. the GPS-fallback manual-location picker). Registration's
/// pre-login ward picker instead calls the separate public endpoint
/// directly - see PublicWardsApi.
class WardsApi {
  WardsApi._();
  static final WardsApi instance = WardsApi._();

  final _client = ApiClient.instance;

  Future<List<Ward>> listActiveWards() async {
    final json = await _client.get('/wards') as List<dynamic>;
    return json.map((e) => Ward.fromJson(e as Map<String, dynamic>)).toList();
  }
}
