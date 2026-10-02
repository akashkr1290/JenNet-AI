import 'dart:math' as math;

import 'location_policy.dart';

/// Where the app's automatic location for a photo came from.
enum DetectedSource {
  /// Phone GPS read right after the photo was taken with the in-app camera.
  captureGps('CAPTURE_GPS'),

  /// The photo's own EXIF GPS (gallery / file).
  exif('EXIF');

  final String apiValue;
  const DetectedSource(this.apiValue);

  static DetectedSource? fromApi(String? v) =>
      DetectedSource.values.where((s) => s.apiValue == v).firstOrNull;
}

/// A point on the map.
class GeoPoint {
  final double latitude;
  final double longitude;
  const GeoPoint(this.latitude, this.longitude);

  Map<String, dynamic> toJson() => {'lat': latitude, 'lng': longitude};

  static GeoPoint? fromJson(Object? json) {
    if (json is! Map) return null;
    final lat = (json['lat'] as num?)?.toDouble();
    final lng = (json['lng'] as num?)?.toDouble();
    if (lat == null || lng == null) return null;
    return GeoPoint(lat, lng);
  }

  @override
  bool operator ==(Object other) => other is GeoPoint && other.latitude == latitude && other.longitude == longitude;

  @override
  int get hashCode => Object.hash(latitude, longitude);

  @override
  String toString() => 'GeoPoint($latitude, $longitude)';
}

/// The location the app found automatically for the photo (proposal only -
/// the citizen always confirms or corrects it on the map).
class DetectedLocation {
  final GeoPoint point;
  final DetectedSource source;

  /// GPS accuracy radius in metres (capture GPS only).
  final double? accuracyMeters;

  const DetectedLocation({required this.point, required this.source, this.accuracyMeters});

  bool get isLowAccuracy => accuracyMeters != null && accuracyMeters! > LocationPolicy.lowAccuracyMeters;

  Map<String, dynamic> toJson() => {
        'point': point.toJson(),
        'source': source.apiValue,
        'accuracy': accuracyMeters,
      };

  static DetectedLocation? fromJson(Object? json) {
    if (json is! Map) return null;
    final point = GeoPoint.fromJson(json['point']);
    final source = DetectedSource.fromApi(json['source'] as String?);
    if (point == null || source == null) return null;
    return DetectedLocation(point: point, source: source, accuracyMeters: (json['accuracy'] as num?)?.toDouble());
  }
}

/// Everything the app knows about the incident location of one complaint.
///
/// INCIDENT LOCATION != SUBMISSION LOCATION: [confirmed] is where the problem
/// is. The citizen's position when submitting is never used for it.
class IncidentLocation {
  /// When the photo was taken (capture time, or EXIF), if known.
  final DateTime? photoTakenAt;

  /// The automatic proposal, or null (no photo location: pin by hand).
  final DetectedLocation? detected;

  /// The point the citizen confirmed on the map, or null until confirmed.
  final GeoPoint? confirmed;

  /// Ward chosen instead of a map point (map unavailable) - server stores an
  /// approximate ward location.
  final int? fallbackWardId;
  final String? fallbackWardName;

  const IncidentLocation({
    this.photoTakenAt,
    this.detected,
    this.confirmed,
    this.fallbackWardId,
    this.fallbackWardName,
  });

  static const none = IncidentLocation();

  bool get isConfirmed => confirmed != null || fallbackWardId != null;

  /// The pin the map should open on.
  GeoPoint? get proposedPoint => confirmed ?? detected?.point;

  IncidentLocation confirm(GeoPoint point) => IncidentLocation(
        photoTakenAt: photoTakenAt,
        detected: detected,
        confirmed: point,
      );

  IncidentLocation useWard(int wardId, String wardName) => IncidentLocation(
        photoTakenAt: photoTakenAt,
        detected: detected,
        fallbackWardId: wardId,
        fallbackWardName: wardName,
      );

  IncidentLocation unconfirmed() => IncidentLocation(photoTakenAt: photoTakenAt, detected: detected);

  /// Whole days between the photo and [now] (null when unknown).
  int? photoAgeDays(DateTime now) {
    if (photoTakenAt == null) return null;
    final days = now.difference(photoTakenAt!).inDays;
    return days < 0 ? 0 : days;
  }

  /// Same rule as the backend's STALE_PHOTO flag.
  bool isStale(DateTime now) =>
      photoTakenAt != null && now.difference(photoTakenAt!) > const Duration(days: LocationPolicy.stalePhotoDays);

  bool isVeryOld(DateTime now) =>
      photoTakenAt != null && now.difference(photoTakenAt!) > const Duration(days: LocationPolicy.veryOldPhotoDays);

  /// One-tap confirmation is offered only for a precise automatic location.
  bool get canConfirmInOneTap => detected != null && !detected!.isLowAccuracy;

  /// Distance between the proposal and the confirmed pin (metres), if both exist.
  double? get pinMovedMeters =>
      detected != null && confirmed != null ? distanceMeters(detected!.point, confirmed!) : null;

  /// Multipart fields for POST /complaints (backend IncidentLocationRequest).
  /// [submissionDistanceMeters]: optional distance to where the citizen is
  /// now - their own position is never sent.
  Map<String, String> toApiFields({double? submissionDistanceMeters}) {
    final fields = <String, String>{'locationConfirmed': 'true'};
    if (confirmed != null) {
      fields['latitude'] = confirmed!.latitude.toStringAsFixed(6);
      fields['longitude'] = confirmed!.longitude.toStringAsFixed(6);
      fields['locationSource'] = _sourceFor(detected, confirmed!);
    } else if (fallbackWardId != null) {
      fields['wardId'] = fallbackWardId.toString();
    }
    if (detected != null) {
      fields['detectedLatitude'] = detected!.point.latitude.toStringAsFixed(6);
      fields['detectedLongitude'] = detected!.point.longitude.toStringAsFixed(6);
      if (detected!.accuracyMeters != null) {
        fields['locationAccuracyMeters'] = detected!.accuracyMeters!.toStringAsFixed(1);
      }
    }
    if (photoTakenAt != null) fields['photoCapturedAt'] = isoWithOffset(photoTakenAt!);
    if (submissionDistanceMeters != null && confirmed != null) {
      fields['submissionDistanceMeters'] = submissionDistanceMeters.toStringAsFixed(1);
    }
    return fields;
  }

  /// CAPTURE_GPS / EXIF when the citizen kept the proposal (within a few
  /// metres - map rounding), otherwise MANUAL_PIN: the citizen placed it.
  static String _sourceFor(DetectedLocation? detected, GeoPoint confirmed) {
    if (detected != null && distanceMeters(detected.point, confirmed) <= 5) return detected.source.apiValue;
    return 'MANUAL_PIN';
  }

  Map<String, dynamic> toJson() => {
        'takenAt': photoTakenAt?.toUtc().toIso8601String(),
        'detected': detected?.toJson(),
        'confirmed': confirmed?.toJson(),
        'wardId': fallbackWardId,
        'wardName': fallbackWardName,
      };

  static IncidentLocation fromJson(Object? json) {
    if (json is! Map) return none;
    return IncidentLocation(
      photoTakenAt: DateTime.tryParse(json['takenAt'] as String? ?? '')?.toLocal(),
      detected: DetectedLocation.fromJson(json['detected']),
      confirmed: GeoPoint.fromJson(json['confirmed']),
      fallbackWardId: (json['wardId'] as num?)?.toInt(),
      fallbackWardName: json['wardName'] as String?,
    );
  }
}

/// Great-circle (haversine) distance in metres - same formula as the backend's
/// LocationFlags.distanceMeters.
double distanceMeters(GeoPoint a, GeoPoint b) {
  const earthRadius = 6371008.8;
  double rad(double d) => d * math.pi / 180;
  final dLat = rad(b.latitude - a.latitude);
  final dLng = rad(b.longitude - a.longitude);
  final h = math.sin(dLat / 2) * math.sin(dLat / 2) +
      math.cos(rad(a.latitude)) * math.cos(rad(b.latitude)) * math.sin(dLng / 2) * math.sin(dLng / 2);
  return 2 * earthRadius * math.asin(math.min(1, math.sqrt(h)));
}

/// ISO-8601 with the device's UTC offset, e.g. 2026-10-02T09:15:00+05:30
/// (the backend's photoCapturedAt format).
String isoWithOffset(DateTime time) {
  final local = time.toLocal();
  final offset = local.timeZoneOffset;
  final sign = offset.isNegative ? '-' : '+';
  final abs = offset.abs();
  String two(int n) => n.toString().padLeft(2, '0');
  final base = '${local.year.toString().padLeft(4, '0')}-${two(local.month)}-${two(local.day)}'
      'T${two(local.hour)}:${two(local.minute)}:${two(local.second)}';
  return '$base$sign${two(abs.inHours)}:${two(abs.inMinutes % 60)}';
}
