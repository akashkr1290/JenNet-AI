import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/complaints/models/complaint.dart';
import 'package:jannet_ai/features/complaints/models/complaint_status.dart';

/// Phase 20: unit tests for the Complaint-family JSON models -
/// ComplaintSummary/ComplaintDetail/ComplaintLocation/ComplaintImage/
/// StatusHistoryEntry/InternalNote's fromJson factories, focused on the
/// null-safety defaults each one applies (a common source of runtime
/// null-check crashes in a Flutter client if the backend's optional
/// fields are ever actually omitted rather than sent as JSON null).
///
/// NOT EXECUTED in this workspace (no `flutter`/`dart` SDK on PATH - see
/// PROJECT_PROGRESS.md's Phase 20 TESTS section). Manually validated
/// against complaint.dart's actual fromJson implementations.
void main() {
  group('ComplaintSummary.fromJson', () {
    test('parses a fully-populated response', () {
      final summary = ComplaintSummary.fromJson({
        'complaintId': 42,
        'referenceNumber': 'JN-2026-000042',
        'category': 'POTHOLE',
        'description': 'Large pothole on Main St',
        'status': 'VERIFIED',
        'severity': 'HIGH',
        'corroborationCount': 3,
        'isEscalated': true,
        'isReopened': false,
        'createdAt': '2026-01-15T10:30:00',
        'updatedAt': '2026-01-16T08:00:00',
      });

      expect(summary.complaintId, 42);
      expect(summary.referenceNumber, 'JN-2026-000042');
      expect(summary.status, ComplaintStatus.verified);
      expect(summary.corroborationCount, 3);
      expect(summary.isEscalated, isTrue);
      expect(summary.createdAt, DateTime.parse('2026-01-15T10:30:00'));
    });

    test('applies documented defaults when optional fields are absent', () {
      final summary = ComplaintSummary.fromJson({
        'complaintId': 1,
        'referenceNumber': 'JN-2026-000001',
        'category': 'GENERAL',
        'status': 'SUBMITTED',
      });

      expect(summary.description, isNull);
      expect(summary.severity, isNull);
      expect(summary.corroborationCount, 1); // documented default
      expect(summary.isEscalated, isFalse);
      expect(summary.isReopened, isFalse);
      expect(summary.createdAt, isNull);
    });

    test('an unparseable createdAt string does not throw, returns null', () {
      final summary = ComplaintSummary.fromJson({
        'complaintId': 1,
        'referenceNumber': 'JN-2026-000001',
        'category': 'GENERAL',
        'status': 'SUBMITTED',
        'createdAt': 'not-a-real-date',
      });

      expect(summary.createdAt, isNull); // DateTime.tryParse swallows the format error
    });
  });

  group('ComplaintDetail.fromJson', () {
    test('parses nested location/images/statusHistory/internalNotes lists', () {
      final detail = ComplaintDetail.fromJson({
        'complaintId': 1,
        'referenceNumber': 'JN-2026-000001',
        'category': 'POTHOLE',
        'status': 'ASSIGNED',
        'corroborationCount': 1,
        'isEscalated': false,
        'isReopened': false,
        'location': {'latitude': 12.97, 'longitude': 77.59, 'wardName': 'Ward 5'},
        'images': [
          {'imageId': 1, 'imageType': 'ORIGINAL', 'contentType': 'image/jpeg'}
        ],
        'statusHistory': [
          {'newStatus': 'SUBMITTED', 'actorType': 'CITIZEN'}
        ],
        'internalNotes': [
          {'logId': 1, 'note': 'Checked on site'}
        ],
      });

      expect(detail.location, isNotNull);
      expect(detail.location!.latitude, 12.97);
      expect(detail.images, hasLength(1));
      expect(detail.statusHistory, hasLength(1));
      expect(detail.internalNotes, hasLength(1));
      expect(detail.internalNotes.first.note, 'Checked on site');
    });

    test('missing location/images/statusHistory/internalNotes default to empty/null, not a crash', () {
      final detail = ComplaintDetail.fromJson({
        'complaintId': 1,
        'referenceNumber': 'JN-2026-000001',
        'category': 'POTHOLE',
        'status': 'SUBMITTED',
        'corroborationCount': 1,
        'isEscalated': false,
        'isReopened': false,
      });

      expect(detail.location, isNull);
      expect(detail.images, isEmpty);
      expect(detail.statusHistory, isEmpty);
      expect(detail.internalNotes, isEmpty); // documented default: const []
    });

    test('internalNotes list is citizen-invisible server-side but the model itself does not filter - documents the trust boundary', () {
      // This model has no knowledge of who's viewing it - the backend
      // (ComplaintService.toResponse) is solely responsible for omitting
      // internalNotes from a citizen's response. This test exists as a
      // sentinel: if this model is ever reused to render a citizen-facing
      // screen with server data that mistakenly included notes, it WILL
      // render them - there is no client-side filter to rely on.
      final detail = ComplaintDetail.fromJson({
        'complaintId': 1,
        'referenceNumber': 'JN-2026-000001',
        'category': 'POTHOLE',
        'status': 'SUBMITTED',
        'corroborationCount': 1,
        'isEscalated': false,
        'isReopened': false,
        'internalNotes': [
          {'logId': 1, 'note': 'Staff-only note'}
        ],
      });
      expect(detail.internalNotes, isNotEmpty);
    });
  });
}
