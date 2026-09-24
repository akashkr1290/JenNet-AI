/// Mirrors the backend's locked ErrorResponse shape
/// (PROJECT_INTEGRATION.md Section 2: `{timestamp, status, error, message,
/// path, details}`) - every non-2xx JSON response uses this shape, so this
/// is the one exception type API calls throw.
class ApiException implements Exception {
  final int status;
  final String error;
  final String message;
  final List<String> details;

  ApiException({
    required this.status,
    required this.error,
    required this.message,
    this.details = const [],
  });

  factory ApiException.fromJson(int status, Map<String, dynamic> json) {
    return ApiException(
      status: status,
      error: (json['error'] as String?) ?? 'ERROR',
      message: (json['message'] as String?) ?? 'Something went wrong',
      details: (json['details'] as List?)?.cast<String>() ?? const [],
    );
  }

  factory ApiException.network() => ApiException(
        status: 0,
        error: 'NETWORK_ERROR',
        message: 'Could not reach the server. Check your connection and try again.',
      );

  @override
  String toString() => message;
}
