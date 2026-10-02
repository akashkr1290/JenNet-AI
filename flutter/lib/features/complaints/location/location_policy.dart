/// Incident-location thresholds used by the app (V33).
///
/// The first three mirror the backend's `app.location.*` settings
/// (backend/src/main/resources/application.yml), which decide the review
/// flags staff see. Keep them in step when either side changes. None of them
/// ever blocks a complaint.
class LocationPolicy {
  LocationPolicy._();

  /// A photo older than this gets the "taken N days ago" hint (STALE_PHOTO).
  static const stalePhotoDays = 7;

  /// GPS accuracy (metres) worse than this needs a check on the map
  /// (LOW_ACCURACY) instead of the one-tap confirmation.
  static const lowAccuracyMeters = 50.0;

  /// The citizen moved the proposed pin further than this (PIN_MOVED_FAR).
  static const pinMovedFarMeters = 200.0;

  /// App only: a photo older than this asks the citizen to tick "The
  /// problem is still there" before submitting - a confirmation, not a block.
  static const veryOldPhotoDays = 30;

  /// A photo whose file was created more than this long before the camera
  /// returned it is not treated as a fresh in-app capture (e.g. iOS Safari's
  /// "camera" button also offers the photo library).
  static const freshCaptureWindow = Duration(minutes: 3);

  /// Minimum map zoom for placing a pin by hand (street level).
  static const pinZoom = 16.0;
  static const minConfirmZoom = 15.0;

  /// How long to wait for a GPS fix after a photo is taken.
  static const captureFixTimeout = Duration(seconds: 20);

  /// "Save & Report Later" drafts are kept this long.
  static const savedDraftLifetime = Duration(days: veryOldPhotoDays);
}
