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
}
