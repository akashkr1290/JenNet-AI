import 'dart:convert';

import 'token_storage.dart';

  /// Phase 12 (Officer Module): the app needs to know the signed-in user's
  /// role client-side for the first time - purely to decide which home
  /// screen/actions to *show* (Citizen vs Officer/Department Head), never to
  /// enforce access. Actual authorization is entirely server-side
  /// (@PreAuthorize + ComplaintService's role checks, see
  /// PROJECT_INTEGRATION.md's "scoped server-side, not merely hidden
  /// client-side" principle) - this is convenience routing only, and a
  /// tampered/absent claim just means the wrong screen renders, not a
  /// security hole, since every backend endpoint re-checks the role anyway.
  ///
  /// Deliberately no new pub dependency (no jwt_decoder package) - the
  /// access token's payload segment is just base64url(JSON), decodable with
  /// dart:convert alone, and JwtService (backend) already puts exactly
  /// {userId, role} in every access token's claims (see its CLAIM_USER_ID/
  /// CLAIM_ROLE constants).
class Session {
  Session._();
  static final Session instance = Session._();

  final _tokens = TokenStorage.instance;

  Future<String?> get role async => (await _claims())?['role'] as String?;

  Future<int?> get userId async {
    final claims = await _claims();
    final raw = claims?['userId'];
    if (raw is int) return raw;
    if (raw is String) return int.tryParse(raw);
    return null;
  }

  Future<bool> get isOfficerOrDepartmentHead async {
    final r = await role;
    return r == 'GOVERNMENT_OFFICER' || r == 'DEPARTMENT_HEAD';
  }

  /// Phase 13 (Department Head Module) addition: distinguishes a
  /// DEPARTMENT_HEAD from a GOVERNMENT_OFFICER specifically - needed for
  /// routing to DepartmentHeadHomeScreen vs OfficerHomeScreen, and for
  /// deciding whether to show the Reassign Officer action on the shared
  /// Officer Complaint Detail screen (GOVERNMENT_OFFICER has no
  /// permission for that action - SRS 16.2's own permission line for
  /// "Reassign" is Department-Head-only; the backend would reject it
  /// with a 403 regardless, same convenience-routing-only caveat as
  /// every other use of this class).
  Future<bool> get isDepartmentHead async {
    final r = await role;
    return r == 'DEPARTMENT_HEAD';
  }

  /// Phase 14 (Admin & Settings Module) addition: routes ADMIN/SUPER_ADMIN
  /// accounts to AdminHomeScreen (home_router.dart) - same convenience-
  /// routing-only caveat as every other getter in this class; every
  /// Admin endpoint re-checks the role server-side via @PreAuthorize
  /// regardless of what this returns.
  Future<bool> get isAdminOrSuperAdmin async {
    final r = await role;
    return r == 'ADMIN' || r == 'SUPER_ADMIN';
  }

  /// Phase 14 addition: distinguishes SUPER_ADMIN from ADMIN specifically -
  /// needed client-side only to decide whether to show role=ADMIN as a
  /// selectable option on the "Add User"/"Change Role" forms
  /// (AdminUserService#requireManageableRole enforces the real rule
  /// server-side regardless - see its Javadoc).
  Future<bool> get isSuperAdmin async {
    final r = await role;
    return r == 'SUPER_ADMIN';
  }

  Future<Map<String, dynamic>?> _claims() async {
    final token = await _tokens.accessToken;
    if (token == null) return null;
    final parts = token.split('.');
    if (parts.length != 3) return null;
    try {
      final normalized = base64Url.normalize(parts[1]);
      final payload = utf8.decode(base64Url.decode(normalized));
      return jsonDecode(payload) as Map<String, dynamic>;
    } catch (_) {
      // Malformed/unexpected token shape - treat as "unknown role" rather
      // than crashing the startup gate; ApiClient/backend still enforce
      // real auth regardless of what this returns.
      return null;
    }
  }
}
