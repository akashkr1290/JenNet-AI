import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart' show MediaType;

import '../auth/token_storage.dart';
import 'api_config.dart';
import 'api_exception.dart';
import 'upload_file.dart';

/// Shared HTTP client for every feature (Auth, Complaints, ...): attaches
/// the Bearer access token, retries exactly once after a transparent
/// token refresh on 401 (POST /api/v1/auth/refresh - AuthController, Phase
/// 4), and always throws [ApiException] on a non-2xx JSON error body so
/// every screen has one error type to catch.
class ApiClient {
  ApiClient._();
  static final ApiClient instance = ApiClient._();

  final http.Client _http = http.Client();
  final TokenStorage _tokens = TokenStorage.instance;

  Uri _uri(String path, [Map<String, dynamic>? query]) {
    final cleanQuery = <String, String>{};
    query?.forEach((k, v) {
      if (v != null) cleanQuery[k] = v.toString();
    });
    return Uri.parse('${ApiConfig.baseUrl}$path').replace(
      queryParameters: cleanQuery.isEmpty ? null : cleanQuery,
    );
  }

  Future<Map<String, String>> _authHeaders() async {
    final token = await _tokens.accessToken;
    return {
      'Content-Type': 'application/json',
      if (token != null) 'Authorization': 'Bearer $token',
    };
  }

  Future<dynamic> get(String path, {Map<String, dynamic>? query}) async {
    return _send(() async => _http.get(_uri(path, query), headers: await _authHeaders()));
  }

  /// Phase 13 addition: for endpoints that return a non-JSON body (e.g.
  /// the Department Performance CSV export, `text/csv`) - [get] always
  /// runs the response through `jsonDecode`, which would throw on a CSV
  /// body. Shares the same auth/401-retry handling as [get] via
  /// [_sendRaw]; the only difference is the response is returned as a
  /// raw string instead of being JSON-decoded.
  Future<String> getRaw(String path, {Map<String, dynamic>? query}) async {
    return _sendRaw(() async => _http.get(_uri(path, query), headers: await _authHeaders()));
  }

  Future<dynamic> post(String path, {Object? body}) async {
    return _send(() async => _http.post(
          _uri(path),
          headers: await _authHeaders(),
          body: body != null ? jsonEncode(body) : null,
        ));
  }

  Future<dynamic> patch(String path, {Object? body}) async {
    return _send(() async => _http.patch(
          _uri(path),
          headers: await _authHeaders(),
          body: body != null ? jsonEncode(body) : null,
        ));
  }

  /// Phase 15 addition: SRS 20.5's `PUT /api/v1/notifications/preferences`
  /// is the first Flutter caller to need a literal PUT (every prior
  /// feature's partial-update endpoints used PATCH) - added as a sibling
  /// of [patch] rather than repurposing it, so the HTTP verb Flutter sends
  /// matches the backend's `@PutMapping` exactly.
  Future<dynamic> put(String path, {Object? body}) async {
    return _send(() async => _http.put(
          _uri(path),
          headers: await _authHeaders(),
          body: body != null ? jsonEncode(body) : null,
        ));
  }

  /// Multipart POST - used by complaint creation (photo + form fields).
  /// [fields] values are stringified as-is; [photo] is attached as `photo`.
  /// Post-UI gap fix: the photo is sent as bytes with its real content type
  /// (see [UploadFile]), so this works on Flutter Web as well as Android.
  Future<dynamic> postMultipart(
    String path, {
    required Map<String, String> fields,
    required UploadFile photo,
  }) async {
    return _sendMultipart('POST', path, fields: fields, filePart: 'photo', file: photo);
  }

  /// Phase 12 addition: multipart PATCH - used by the Officer status
  /// update action (SRS Table 8), where [file] (the optional/conditional
  /// after_photo) may be null - unlike [postMultipart]'s photo, which is
  /// always required at complaint creation.
  Future<dynamic> patchMultipart(
    String path, {
    required Map<String, String> fields,
    String filePart = 'afterPhoto',
    UploadFile? file,
  }) async {
    return _sendMultipart('PATCH', path, fields: fields, filePart: filePart, file: file);
  }

  Future<dynamic> _sendMultipart(
    String method,
    String path, {
    required Map<String, String> fields,
    required String filePart,
    UploadFile? file,
  }) async {
    return _send(() async {
      final token = await _tokens.accessToken;
      final request = http.MultipartRequest(method, _uri(path));
      if (token != null) request.headers['Authorization'] = 'Bearer $token';
      request.fields.addAll(fields);
      if (file != null) {
        // Bytes + explicit content type: no dart:io file access (works in
        // browsers), and the type is what the backend's photo check reads.
        request.files.add(http.MultipartFile.fromBytes(
          filePart,
          file.bytes,
          filename: file.filename,
          contentType: MediaType.parse(file.contentType),
        ));
      }
      final streamed = await _http.send(request);
      return http.Response.fromStream(streamed);
    });
  }

  Future<dynamic> _send(Future<http.Response> Function() call, {bool isRetry = false}) async {
    final response = await _sendForResponse(call, isRetry: isRetry);
    if (response.body.isEmpty) return null;
    return jsonDecode(response.body);
  }

  /// Phase 13 addition: shares [_send]'s auth/401-retry/error-throwing
  /// behavior via [_sendForResponse] but returns the raw response body
  /// string instead of JSON-decoding it - see [getRaw]'s Javadoc-
  /// equivalent comment for why (a CSV export response would otherwise
  /// crash `jsonDecode`).
  Future<String> _sendRaw(Future<http.Response> Function() call, {bool isRetry = false}) async {
    final response = await _sendForResponse(call, isRetry: isRetry);
    return response.body;
  }

  Future<http.Response> _sendForResponse(Future<http.Response> Function() call, {bool isRetry = false}) async {
    http.Response response;
    try {
      response = await call();
    } on http.ClientException {
      throw ApiException.network();
    } on SocketException {
      throw ApiException.network();
    }

    if (response.statusCode == 401 && !isRetry) {
      final refreshed = await _tryRefresh();
      if (refreshed) {
        return _sendForResponse(call, isRetry: true);
      }
    }

    if (response.statusCode >= 200 && response.statusCode < 300) {
      return response;
    }

    Map<String, dynamic> errorJson = {};
    try {
      errorJson = jsonDecode(response.body) as Map<String, dynamic>;
    } catch (_) {
      // Non-JSON error body (e.g. a 413 from the servlet container itself,
      // before GlobalExceptionHandler gets a chance) - fall back to a
      // generic message rather than crashing on the parse.
    }
    throw ApiException.fromJson(response.statusCode, errorJson);
  }

  /// Audit GAP-025: the refresh currently in progress, shared by every caller.
  /// Refresh tokens rotate on use and a second use of the same token is
  /// treated as theft (the whole token family is revoked, logging the user
  /// out). When several requests hit 401 together (typical after the access
  /// token expires on a dashboard that loads in parallel) they must all await
  /// ONE /auth/refresh call instead of each sending the same refresh token.
  Future<bool>? _refreshInFlight;

  Future<bool> _tryRefresh() {
    final inFlight = _refreshInFlight;
    if (inFlight != null) return inFlight;
    final refresh = _refreshOnce();
    _refreshInFlight = refresh;
    refresh.whenComplete(() {
      if (identical(_refreshInFlight, refresh)) _refreshInFlight = null;
    }).ignore(); // callers observe the result/error through `refresh` itself

    return refresh;
  }

  Future<bool> _refreshOnce() async {
    final refreshToken = await _tokens.refreshToken;
    if (refreshToken == null) return false;
    try {
      final response = await _http.post(
        _uri('/auth/refresh'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({'refreshToken': refreshToken}),
      );
      if (response.statusCode != 200) {
        await _tokens.clear();
        return false;
      }
      final json = jsonDecode(response.body) as Map<String, dynamic>;
      await _tokens.saveTokens(
        accessToken: json['accessToken'] as String,
        refreshToken: json['refreshToken'] as String,
      );
      return true;
    } catch (_) {
      return false;
    }
  }
}
