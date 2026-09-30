import '../../core/api/api_client.dart';

/// Mirrors UserProfileResponse (backend, dto/auth/) - deliberately a
/// small standalone class here rather than importing a citizen-profile
/// model from elsewhere, since no Flutter feature has needed the
/// authenticated user's own profile shape until now.
class UserProfile {
  final int userId;
  final String fullName;
  final String role;
  final int? departmentId;

  // Post-UI gap fix (My Profile screen, SRS 15.1 "profile management" and
  // "reputation score display"): the remaining UserProfileResponse fields.
  // All nullable so older/partial payloads still parse.
  final String? mobileNumber;
  final String? email;
  final String? status;
  final int? reputationScore;
  final int? wardId;

  /// Pilot 2026-09-30: names for the side bar ("which department is this").
  final String? departmentName;
  final String? wardName;

  UserProfile({
    required this.userId,
    required this.fullName,
    required this.role,
    this.departmentId,
    this.mobileNumber,
    this.email,
    this.status,
    this.reputationScore,
    this.wardId,
    this.departmentName,
    this.wardName,
  });

  factory UserProfile.fromJson(Map<String, dynamic> json) {
    return UserProfile(
      userId: json['userId'] as int,
      fullName: json['fullName'] as String,
      role: json['role'] as String,
      departmentId: json['departmentId'] as int?,
      mobileNumber: json['mobileNumber'] as String?,
      email: json['email'] as String?,
      status: json['status'] as String?,
      reputationScore: (json['reputationScore'] as num?)?.toInt(),
      wardId: (json['wardId'] as num?)?.toInt(),
      departmentName: json['departmentName'] as String?,
      wardName: json['wardName'] as String?,
    );
  }

  /// Human-readable role, e.g. "Department Head".
  static String roleLabel(String role) => switch (role) {
        'CITIZEN' => 'Citizen',
        'GOVERNMENT_OFFICER' => 'Government Officer',
        'VERIFICATION_TEAM' => 'Verification Team',
        'DEPARTMENT_HEAD' => 'Department Head',
        'ADMIN' => 'Administrator',
        'SUPER_ADMIN' => 'Super Administrator',
        _ => role.replaceAll('_', ' '),
      };

  /// Where this person works: their department, or (citizens) their ward.
  String? get unitName => departmentName ?? (role == 'CITIZEN' ? wardName : null);

  /// Side-bar line under the name, e.g. "Department Head · Public Works".
  String get roleAndUnit => [roleLabel(role), if (unitName != null && unitName!.isNotEmpty) unitName!].join(' · ');
}

/// Phase 13 (Department Head Module) addition: `GET /api/v1/users/me`
/// has existed since Phase 4, but no Flutter screen has needed the
/// signed-in user's own profile until now - a DEPARTMENT_HEAD's home
/// screen needs to know their own `departmentId` (not present in the
/// JWT payload - see Session's Javadoc-equivalent comment: the token
/// only carries `uid`/`role`) before it can call any of the new
/// `/departments/{id}/...` endpoints, which are hard-scoped server-side
/// to that same department (see DepartmentApi's Javadoc).
class UserApi {
  UserApi._();
  static final UserApi instance = UserApi._();

  final _client = ApiClient.instance;

  Future<UserProfile> me() async {
    final json = await _client.get('/users/me') as Map<String, dynamic>;
    return UserProfile.fromJson(json);
  }

  /// PUT /api/v1/users/me (UserController, Phase 5 - UpdateProfileRequest:
  /// `fullName` + `wardId` only). NOTE: `wardId: null` CLEARS the ward
  /// server-side, so callers must pass the current ward to keep it.
  Future<UserProfile> updateMe({required String fullName, required int? wardId}) async {
    final json = await _client.put('/users/me', body: {
      'fullName': fullName,
      'wardId': wardId,
    }) as Map<String, dynamic>;
    return UserProfile.fromJson(json);
  }

  /// Audit GAP-041 (SRS 24 data access request): all personal data the
  /// platform stores about the signed-in user, as pretty JSON text.
  Future<String> exportMyData() => _client.getRaw('/users/me/data-export');

  /// Audit GAP-041 (SRS 24 erasure request): irreversibly removes the
  /// signed-in citizen's personal data; the account can no longer sign in.
  Future<void> eraseMyAccount(String password) async {
    await _client.post('/users/me/erase', body: {'password': password});
  }
}
