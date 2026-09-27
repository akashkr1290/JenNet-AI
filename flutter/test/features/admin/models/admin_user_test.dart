import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/admin/models/admin_user.dart';

/// Live pilot bug: staff temporary passwords are now e-mailed by the backend
/// and never returned by POST /admin/users - the result only names the
/// masked address they went to.
void main() {
  test('AdminCreateUserResult parses the delivery address and carries no password', () {
    final result = AdminCreateUserResult.fromJson({
      'user': {
        'userId': 12,
        'fullName': 'Demo Officer PWD',
        'mobileNumber': '9000000301',
        'email': 'officer@example.org',
        'role': 'GOVERNMENT_OFFICER',
        'status': 'ACTIVE',
        'reputationScore': 100,
        'wardId': null,
        'departmentId': 1,
      },
      'passwordDeliveredTo': 'o***@example.org',
    });

    expect(result.user.role, 'GOVERNMENT_OFFICER');
    expect(result.user.departmentId, 1);
    expect(result.passwordDeliveredTo, 'o***@example.org');
  });
}
