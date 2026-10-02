import 'dart:async';

import 'package:geolocator/geolocator.dart';

import 'incident_location.dart';
import 'location_policy.dart';

/// Why no GPS fix could be read.
enum PositionFailure { servicesOff, denied, deniedForever, timeout, error }

/// One GPS reading (or why there is none).
class PositionResult {
  final GeoPoint? point;
  final double? accuracyMeters;
  final PositionFailure? failure;

  const PositionResult.fix(GeoPoint this.point, this.accuracyMeters) : failure = null;
  const PositionResult.failed(PositionFailure this.failure)
      : point = null,
        accuracyMeters = null;

  bool get ok => point != null;
}

/// Reads the phone's position. Used only:
///  * right after the citizen takes a photo with the in-app camera (the
///    capture location - permission is asked at that moment, not at start-up);
///  * when the citizen taps "You are here" on the map (a navigation helper,
///    never stored as the incident location);
///  * [ifAlreadyAllowed] at submission, to compute only a distance.
class DevicePosition {
  DevicePosition._();

  static Future<PositionResult> read({
    bool askPermission = true,
    Duration timeout = LocationPolicy.captureFixTimeout,
    LocationAccuracy accuracy = LocationAccuracy.high,
  }) async {
    try {
      if (!await Geolocator.isLocationServiceEnabled()) {
        return const PositionResult.failed(PositionFailure.servicesOff);
      }
      var permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied && askPermission) {
        permission = await Geolocator.requestPermission();
      }
      if (permission == LocationPermission.deniedForever) {
        return const PositionResult.failed(PositionFailure.deniedForever);
      }
      if (permission == LocationPermission.denied || permission == LocationPermission.unableToDetermine) {
        return const PositionResult.failed(PositionFailure.denied);
      }
      // geolocator 12 (the locked version): accuracy + timeLimit parameters.
      final position = await Geolocator.getCurrentPosition(desiredAccuracy: accuracy, timeLimit: timeout);
      return PositionResult.fix(
        GeoPoint(position.latitude, position.longitude),
        position.accuracy.isFinite && position.accuracy > 0 ? position.accuracy : null,
      );
    } on TimeoutException {
      return const PositionResult.failed(PositionFailure.timeout);
    } catch (_) {
      return const PositionResult.failed(PositionFailure.error);
    }
  }

  /// A quick reading only when permission was already granted (no prompt).
  static Future<PositionResult> ifAlreadyAllowed() =>
      read(askPermission: false, timeout: const Duration(seconds: 6), accuracy: LocationAccuracy.medium);

  /// Plain-language reason for the citizen.
  static String explain(PositionFailure failure) => switch (failure) {
        PositionFailure.servicesOff => 'Location is turned off on this device.',
        PositionFailure.denied || PositionFailure.deniedForever => 'Location permission was not given.',
        PositionFailure.timeout => 'Your location could not be found in time.',
        PositionFailure.error => 'Your location could not be found.',
      };
}
