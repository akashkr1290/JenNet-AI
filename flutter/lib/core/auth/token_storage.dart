import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Thin wrapper over flutter_secure_storage - JWT access/refresh tokens are
/// exactly the kind of value that must not sit in plain SharedPreferences
/// (SRS 27: bearer tokens are the whole auth story client-side).
class TokenStorage {
  TokenStorage._();
  static final TokenStorage instance = TokenStorage._();

  final _storage = const FlutterSecureStorage();

  static const _accessTokenKey = 'jannet_access_token';
  static const _refreshTokenKey = 'jannet_refresh_token';

  Future<void> saveTokens({required String accessToken, required String refreshToken}) async {
    await _storage.write(key: _accessTokenKey, value: accessToken);
    await _storage.write(key: _refreshTokenKey, value: refreshToken);
  }

  Future<String?> get accessToken => _storage.read(key: _accessTokenKey);
  Future<String?> get refreshToken => _storage.read(key: _refreshTokenKey);

  Future<void> clear() async {
    await _storage.delete(key: _accessTokenKey);
    await _storage.delete(key: _refreshTokenKey);
  }
}
