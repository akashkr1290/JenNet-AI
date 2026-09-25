import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/core/api/api_exception.dart';
import 'package:jannet_ai/core/platform_status.dart';

/// Audit GAP-037: platform status parsing and the maintenance-refusal check
/// used to keep complaint submissions queued. NOT EXECUTED in the workspace
/// that wrote it (no Flutter SDK there) - run with `flutter test`.
void main() {
  test('parses the public platform-status response', () {
    final s = PlatformStatus.fromJson({
      'maintenanceMode': true,
      'maintenanceMessage': 'Back at 6 pm',
      'retryAfterSeconds': 600,
      'announcement': 'Water supply notice',
    });
    expect(s.maintenanceMode, isTrue);
    expect(s.maintenanceMessage, 'Back at 6 pm');
    expect(s.retryAfterSeconds, 600);
    expect(s.hasBanner, isTrue);
  });

  test('no banner when neither maintenance nor an announcement is set', () {
    expect(PlatformStatus.fromJson({'maintenanceMode': false}).hasBanner, isFalse);
    expect(PlatformStatus.fromJson({'maintenanceMode': false, 'announcement': '  '}).hasBanner, isFalse);
  });

  test('only a 503 MAINTENANCE answer counts as a maintenance refusal', () {
    expect(isMaintenanceRefusal(ApiException(status: 503, error: 'MAINTENANCE', message: 'x')), isTrue);
    expect(isMaintenanceRefusal(ApiException(status: 503, error: 'SERVICE_UNAVAILABLE', message: 'x')), isFalse);
    expect(isMaintenanceRefusal(ApiException(status: 400, error: 'MAINTENANCE', message: 'x')), isFalse);
    expect(isMaintenanceRefusal(Exception('offline')), isFalse);
  });
}
