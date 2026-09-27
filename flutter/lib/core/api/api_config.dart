/// Matches the root .env.example convention (`API_BASE_URL=http://localhost:8080/api/v1`).
/// Flutter has no .env reader wired up yet (that's a build-tooling decision
/// for a later phase - flutter_dotenv vs. --dart-define, not decided here),
/// so for now this reads a --dart-define of the same name with the same
/// default, e.g.:
///   flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080/api/v1
class ApiConfig {
  ApiConfig._();

  static const String baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://localhost:8080/api/v1',
  );

  /// Turns a URL the backend returned into one the app can load directly.
  /// The backend sends some links host-relative (e.g. a complaint photo's
  /// signed viewUrl `/api/v1/images/content?...`, see backend
  /// LocalStorageService#presignedUrl), which must be resolved against the
  /// API's own origin - left as-is, Flutter Web would request it from the web
  /// app's origin (Cloudflare Pages), which has no such route, and a mobile
  /// build could not load it at all. Absolute URLs (e.g. S3 presigned links)
  /// are returned unchanged.
  static String resolve(String url) => Uri.parse(baseUrl).resolve(url).toString();
}
