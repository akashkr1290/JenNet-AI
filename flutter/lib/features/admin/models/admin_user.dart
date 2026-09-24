/// Mirrors UserProfileResponse (backend, dto/auth/) - reused as the row
/// shape for GET /api/v1/admin/users and the response of every mutating
/// admin/users/* action (all return the updated profile, per
/// AdminUserController).
class AdminUser {
  final int userId;
  final String fullName;
  final String mobileNumber;
  final String? email;
  final String role;
  final String status;
  final int reputationScore;
  final int? wardId;
  final int? departmentId;

  AdminUser({
    required this.userId,
    required this.fullName,
    required this.mobileNumber,
    this.email,
    required this.role,
    required this.status,
    required this.reputationScore,
    this.wardId,
    this.departmentId,
  });

  factory AdminUser.fromJson(Map<String, dynamic> json) {
    return AdminUser(
      userId: json['userId'] as int,
      fullName: json['fullName'] as String,
      mobileNumber: json['mobileNumber'] as String,
      email: json['email'] as String?,
      role: json['role'] as String,
      status: json['status'] as String,
      reputationScore: json['reputationScore'] as int,
      wardId: json['wardId'] as int?,
      departmentId: json['departmentId'] as int?,
    );
  }
}

/// Mirrors AdminCreateUserResponse (backend, dto/admin/) - the one-time
/// temporary password is only ever present in this response, right after
/// POST /api/v1/admin/users - see AdminUserService's Javadoc for why.
class AdminCreateUserResult {
  final AdminUser user;
  final String temporaryPassword;

  AdminCreateUserResult({required this.user, required this.temporaryPassword});

  factory AdminCreateUserResult.fromJson(Map<String, dynamic> json) {
    return AdminCreateUserResult(
      user: AdminUser.fromJson(json['user'] as Map<String, dynamic>),
      temporaryPassword: json['temporaryPassword'] as String,
    );
  }
}

/// Every role AdminUserService will accept as a target for creation/role
/// change - mirrors the entity.enums.Role values minus CITIZEN and
/// SUPER_ADMIN, which AdminUserService#requireManageableRole always
/// rejects (see its Javadoc's "ROLE-PRIVILEGE MATRIX"). ADMIN is included
/// here because it's a valid *target* role for a SUPER_ADMIN actor -
/// the screen itself decides whether to show it as a selectable option
/// based on the signed-in user's own role (see AdminUserManagementScreen).
const List<String> manageableStaffRoles = [
  'GOVERNMENT_OFFICER',
  'DEPARTMENT_HEAD',
  'VERIFICATION_TEAM',
  'MAINTENANCE_TEAM',
  'ADMIN',
];
