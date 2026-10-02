import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/complaints/complaint_draft_service.dart';
import 'package:jannet_ai/features/complaints/location/device_position.dart';
import 'package:jannet_ai/features/complaints/location/incident_location.dart';
import 'package:jannet_ai/features/complaints/location/photo_exif.dart';
import 'package:jannet_ai/features/complaints/location/photo_intake.dart';
import 'package:jannet_ai/features/complaints/pending_submission_sync.dart';

// V33: INCIDENT LOCATION != SUBMISSION LOCATION.
const _a = GeoPoint(28.6139, 77.2090); // where the problem is (photo taken here)
const _home = GeoPoint(28.5355, 77.3910); // where the citizen is later (~20 km)

void main() {
  final now = DateTime(2026, 10, 2, 12);

  group('automatic proposal (decideProposal)', () {
    test('A: in-app camera + GPS fix -> capture GPS proposal with accuracy and time', () {
      final r = decideProposal(
        fresh: true,
        fix: const PositionResult.fix(_a, 8),
        exif: PhotoExif.empty,
        returnedAt: now,
      );
      expect(r.location.detected!.source, DetectedSource.captureGps);
      expect(r.location.detected!.point, _a);
      expect(r.location.detected!.accuracyMeters, 8);
      expect(r.location.photoTakenAt, now);
      expect(r.location.isConfirmed, isFalse, reason: 'the citizen must still confirm it');
      expect(r.note, isNull);
    });

    test('C: gallery photo with EXIF GPS -> EXIF proposal, photo date from EXIF', () {
      final taken = DateTime(2026, 9, 30, 9);
      final r = decideProposal(
        fresh: false,
        fix: null,
        exif: PhotoExif(latitude: _a.latitude, longitude: _a.longitude, takenAt: taken),
        returnedAt: now,
      );
      expect(r.location.detected!.source, DetectedSource.exif);
      expect(r.location.photoTakenAt, taken);
    });

    test('D/J: gallery photo without GPS -> no proposal, manual map pin required', () {
      final r = decideProposal(fresh: false, fix: null, exif: PhotoExif.empty, returnedAt: now);
      expect(r.location.detected, isNull);
      expect(r.location.canConfirmInOneTap, isFalse);
      expect(r.note, contains('no location'));
    });

    test('E/F: camera photo but GPS off / permission denied -> EXIF if any, else manual pin', () {
      final off = decideProposal(
          fresh: true, fix: const PositionResult.failed(PositionFailure.servicesOff), exif: PhotoExif.empty, returnedAt: now);
      expect(off.location.detected, isNull);
      expect(off.note, contains('turned off'));
      expect(off.location.photoTakenAt, now);

      final denied = decideProposal(
          fresh: true, fix: const PositionResult.failed(PositionFailure.denied), exif: PhotoExif.empty, returnedAt: now);
      expect(denied.note, contains('permission'));

      final withExif = decideProposal(
        fresh: true,
        fix: const PositionResult.failed(PositionFailure.denied),
        exif: PhotoExif(latitude: _a.latitude, longitude: _a.longitude),
        returnedAt: now,
      );
      expect(withExif.location.detected!.source, DetectedSource.exif);
    });

    test('a gallery photo NEVER gets the phone position', () {
      // fresh=false means no GPS read at all; even a fix passed in is ignored.
      final r = decideProposal(
          fresh: false, fix: const PositionResult.fix(_home, 5), exif: PhotoExif.empty, returnedAt: now);
      expect(r.location.detected, isNull);
    });

    test('web camera button: only a just-created file counts as a fresh capture', () {
      expect(isFreshCapture(now.subtract(const Duration(seconds: 20)), now), isTrue);
      expect(isFreshCapture(now.subtract(const Duration(days: 3)), now), isFalse);
      expect(isFreshCapture(null, now), isFalse);
    });
  });

  group('confirmation and API fields', () {
    final proposal = IncidentLocation(
      photoTakenAt: now,
      detected: const DetectedLocation(point: _a, source: DetectedSource.captureGps, accuracyMeters: 8),
    );

    test('one-tap confirmation keeps the capture source', () {
      final fields = proposal.confirm(_a).toApiFields();
      expect(fields['locationConfirmed'], 'true');
      expect(fields['latitude'], '28.613900');
      expect(fields['longitude'], '77.209000');
      expect(fields['locationSource'], 'CAPTURE_GPS');
      expect(fields['detectedLatitude'], '28.613900');
      expect(fields['locationAccuracyMeters'], '8.0');
      expect(fields['photoCapturedAt'], isoWithOffset(now));
      expect(fields.containsKey('wardId'), isFalse);
    });

    test('B/K: reported later from home - the incident stays at A, only a distance is sent', () {
      final fields = proposal.confirm(_a).toApiFields(submissionDistanceMeters: distanceMeters(_home, _a));
      expect(fields['latitude'], '28.613900');
      expect(fields['longitude'], '77.209000');
      expect(double.parse(fields['submissionDistanceMeters']!), greaterThan(15000));
      expect(fields.values.join(' '), isNot(contains('28.5355')), reason: 'home coordinates are never sent');
    });

    test('G: moving the pin makes it a manual pin and keeps the detected point', () {
      const moved = GeoPoint(28.6160, 77.2090); // ~230 m
      final confirmed = proposal.confirm(moved);
      final fields = confirmed.toApiFields();
      expect(fields['locationSource'], 'MANUAL_PIN');
      expect(fields['latitude'], '28.616000');
      expect(fields['detectedLatitude'], '28.613900');
      expect(confirmed.pinMovedMeters, greaterThan(200));
    });

    test('H: poor accuracy needs a map check instead of one tap', () {
      const poor = IncidentLocation(
        detected: DetectedLocation(point: _a, source: DetectedSource.captureGps, accuracyMeters: 120),
      );
      expect(poor.detected!.isLowAccuracy, isTrue);
      expect(poor.canConfirmInOneTap, isFalse);
      expect(proposal.canConfirmInOneTap, isTrue);
    });

    test('I: stale and very old photos', () {
      expect(IncidentLocation(photoTakenAt: now.subtract(const Duration(days: 3))).isStale(now), isFalse);
      final week = IncidentLocation(photoTakenAt: now.subtract(const Duration(days: 9)));
      expect(week.isStale(now), isTrue);
      expect(week.isVeryOld(now), isFalse);
      expect(week.photoAgeDays(now), 9);
      expect(IncidentLocation(photoTakenAt: now.subtract(const Duration(days: 40))).isVeryOld(now), isTrue);
      expect(IncidentLocation.none.isStale(now), isFalse);
    });

    test('ward fallback sends the ward and no coordinates', () {
      final fields = IncidentLocation.none.useWard(7, 'Ward 7').toApiFields(submissionDistanceMeters: 12);
      expect(fields['wardId'], '7');
      expect(fields['locationConfirmed'], 'true');
      expect(fields.containsKey('latitude'), isFalse);
      expect(fields.containsKey('submissionDistanceMeters'), isFalse);
    });

    test('not confirmed until the citizen confirms', () {
      expect(proposal.isConfirmed, isFalse);
      expect(proposal.confirm(_a).isConfirmed, isTrue);
      expect(proposal.confirm(_a).unconfirmed().isConfirmed, isFalse);
    });

    test('isoWithOffset carries the device offset', () {
      expect(isoWithOffset(now), matches(RegExp(r'^2026-10-02T12:00:00[+-]\d{2}:\d{2}$')));
    });

    test('distance matches the backend haversine', () {
      expect(distanceMeters(const GeoPoint(28.60, 77.20), const GeoPoint(28.61, 77.20)), closeTo(1112, 12));
    });
  });

  group('Save & Report Later draft', () {
    final location = IncidentLocation(
      photoTakenAt: now.subtract(const Duration(days: 2)),
      detected: const DetectedLocation(point: _a, source: DetectedSource.captureGps, accuracyMeters: 9),
    ).confirm(_a);

    test('round-trips the photo location and time (B: finish later at home)', () {
      final raw = ComplaintDraftService.encodeDraft(
        description: 'Pothole',
        location: location,
        stillThere: true,
        savedForLater: true,
        savedAt: now,
      );
      final draft = ComplaintDraftService.decodeDraft(raw, now: now.add(const Duration(days: 5)))!;
      expect(draft.description, 'Pothole');
      expect(draft.location.confirmed, _a);
      expect(draft.location.detected!.source, DetectedSource.captureGps);
      expect(draft.location.detected!.accuracyMeters, 9);
      expect(draft.location.photoTakenAt, location.photoTakenAt);
      expect(draft.stillThere, isTrue);
      expect(draft.savedForLater, isTrue);
    });

    test('automatic drafts expire after 24 hours, saved-for-later after 30 days', () {
      String encode(bool later) => ComplaintDraftService.encodeDraft(
          location: location, stillThere: false, savedForLater: later, savedAt: now);
      expect(ComplaintDraftService.decodeDraft(encode(false), now: now.add(const Duration(hours: 25))), isNull);
      expect(ComplaintDraftService.decodeDraft(encode(true), now: now.add(const Duration(days: 29))), isNotNull);
      expect(ComplaintDraftService.decodeDraft(encode(true), now: now.add(const Duration(days: 31))), isNull);
      expect(ComplaintDraftService.decodeDraft('not json', now: now), isNull);
    });
  });

  group('offline queue', () {
    test('a queued item sends its confirmed incident fields unchanged', () {
      final fields = PendingSubmissionSync.locationFieldsOf({
        'locationFields': {'latitude': '28.613900', 'longitude': '77.209000', 'locationConfirmed': 'true'},
      });
      expect(fields, {'latitude': '28.613900', 'longitude': '77.209000', 'locationConfirmed': 'true'});
    });

    test('items queued by the previous app version are still sent as before', () {
      final fields = PendingSubmissionSync.locationFieldsOf(
          {'latitude': 12.5, 'longitude': 77.5, 'wardId': null, 'locationSource': 'DEVICE_GPS'});
      expect(fields, {'latitude': '12.5', 'longitude': '77.5', 'locationSource': 'DEVICE_GPS'});
    });
  });
}
