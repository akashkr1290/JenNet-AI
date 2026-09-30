import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/complaints/models/complaint.dart';
import 'package:jannet_ai/features/complaints/models/complaint_status.dart';
import 'package:jannet_ai/features/department/models/department_performance.dart';

/// Pilot workflow 2026-09-30: AI / Verification Team -> department -> the
/// Department Head assigns a Government Officer -> officer resolves with note +
/// photo -> citizen confirms, or it closes automatically.
void main() {
  Map<String, dynamic> base(Map<String, dynamic> extra) => {
        'complaintId': 5,
        'referenceNumber': 'JN-2026-000005',
        'category': 'POTHOLE',
        'status': 'VERIFIED',
        ...extra,
      };

  group('ComplaintDetail workflow fields', () {
    test('a routed complaint waits for the Department Head to assign an officer', () {
      final c = ComplaintDetail.fromJson(base({'departmentId': 1, 'departmentName': 'Public Works'}));
      expect(c.status, ComplaintStatus.verified);
      expect(c.departmentName, 'Public Works');
      expect(c.assignedOfficerName, isNull);
      expect(c.awaitingOfficer, isTrue);
    });

    test('a complaint with an officer is no longer waiting', () {
      final c = ComplaintDetail.fromJson(base({
        'status': 'ASSIGNED',
        'departmentId': 1,
        'assignedOfficerId': 20,
        'assignedOfficerName': 'Asha Rao',
      }));
      expect(c.awaitingOfficer, isFalse);
      expect(c.assignedOfficerName, 'Asha Rao');
    });

    test('an unrouted complaint (no department yet) is not waiting on a Department Head', () {
      expect(ComplaintDetail.fromJson(base({})).awaitingOfficer, isFalse);
    });

    test('resolution note is the latest move to Resolved and the auto-close time is read', () {
      final c = ComplaintDetail.fromJson(base({
        'status': 'RESOLVED',
        'departmentId': 1,
        'assignedOfficerId': 20,
        'autoCloseAt': '2026-10-03T10:00:00',
        'statusHistory': [
          {'newStatus': 'RESOLVED', 'actorType': 'OFFICER', 'actorName': 'Asha Rao', 'reason': 'First fix'},
          {'newStatus': 'IN_PROGRESS', 'actorType': 'CITIZEN', 'reason': 'Citizen reopened complaint'},
          {
            'newStatus': 'RESOLVED',
            'actorType': 'OFFICER',
            'actorName': 'Asha Rao',
            'reason': 'Pothole filled and levelled',
            'changedAt': '2026-09-30T10:00:00',
          },
        ],
      }));
      expect(c.resolutionEntry?.reason, 'Pothole filled and levelled');
      expect(c.resolutionEntry?.actorName, 'Asha Rao');
      expect(c.autoCloseAt, DateTime.utc(2026, 10, 3, 10).toLocal());
    });

    test('older payloads without the new fields still parse', () {
      final c = ComplaintDetail.fromJson(base({'status': 'IN_PROGRESS'}));
      expect(c.departmentName, isNull);
      expect(c.autoCloseAt, isNull);
      expect(c.resolutionEntry, isNull);
    });
  });

  group('OfficerSummary (Department Head officer picker)', () {
    test('shows availability and open workload', () {
      final o = OfficerSummary.fromJson({
        'userId': 20,
        'fullName': 'Asha Rao',
        'availability': 'ON_LEAVE',
        'openComplaints': 2,
      });
      expect(o.pickerLabel, 'Asha Rao · On leave · 2 open');
    });

    test('defaults to available with no open complaints', () {
      final o = OfficerSummary.fromJson({'userId': 21, 'fullName': 'Bala'});
      expect(o.availability, 'AVAILABLE');
      expect(o.pickerLabel, 'Bala · Available · 0 open');
    });
  });
}
