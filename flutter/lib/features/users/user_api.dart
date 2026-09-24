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

  UserProfile({required this.userId, required this.fullName, required this.role, this.departmentId});

  factory UserProfile.fromJson(Map<String, dynamic> json) {
    return UserProfile(
      userId: json['userId'] as int,
      fullName: json['fullName'] as String,
      role: json['role'] as String,
      departmentId: json['departmentId'] as int?,
    );
  }
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
}
