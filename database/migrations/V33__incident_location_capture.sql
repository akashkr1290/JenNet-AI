-- V33__incident_location_capture.sql
-- Incident location (product owner decision, 2026-10-02):
--
--   INCIDENT LOCATION != SUBMISSION LOCATION.
--
-- `locations` stays the record of WHERE THE PROBLEM IS (ward, jurisdiction,
-- duplicate detection, heatmaps, map). The citizen now confirms that point
-- on a map before submitting; it comes from, in priority order:
--   1. CAPTURE_GPS  - phone GPS read right after an in-app camera capture
--   2. EXIF         - the photo's own GPS metadata
--   3. MANUAL_PIN   - a pin the citizen placed on the map
--   4. WARD_FALLBACK - ward chosen when the map cannot be used (server-set)
-- DEVICE_GPS remains valid for complaints from older app versions (GPS read
-- when the screen opened - the behaviour this release corrects).
--
-- New columns (all NULLable or defaulted - every existing row stays valid
-- and older app versions keep working unchanged):
--   accuracy_m            GPS accuracy radius reported by the device
--   captured_at           when the photo was taken (UTC), when known
--   detected_latitude/    the location proposed automatically (capture GPS
--   detected_longitude    or EXIF). Kept when the citizen moves the pin, so
--                         staff can see both points.
--   confirmed_by_citizen  TRUE when the citizen confirmed the point on the
--                         map; FALSE for older clients / existing rows
--   flags                 comma-separated review flags: STALE_PHOTO,
--                         LOW_ACCURACY, PIN_MOVED_FAR, NO_PHOTO_LOCATION,
--                         OUT_OF_JURISDICTION (warnings, never blocking)
--   submission_distance_m distance between the incident point and where the
--                         citizen was when submitting, when known. The
--                         citizen's own (home/submission) coordinates are
--                         deliberately NOT stored.
--
-- Rollback note: v0.1.8 runs unchanged against this schema (it never reads
-- the new columns), but rows created with source CAPTURE_GPS would fail to
-- load in v0.1.8 (unknown enum constant) - roll back data with
--   UPDATE locations SET source = 'DEVICE_GPS' WHERE source = 'CAPTURE_GPS';

ALTER TABLE locations ADD COLUMN accuracy_m DECIMAL(9,1) NULL;
ALTER TABLE locations ADD COLUMN captured_at DATETIME NULL;
ALTER TABLE locations ADD COLUMN detected_latitude DECIMAL(9,6) NULL;
ALTER TABLE locations ADD COLUMN detected_longitude DECIMAL(9,6) NULL;
ALTER TABLE locations ADD COLUMN confirmed_by_citizen BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE locations ADD COLUMN flags VARCHAR(200) NULL;
ALTER TABLE locations ADD COLUMN submission_distance_m DECIMAL(10,1) NULL;

ALTER TABLE locations DROP CONSTRAINT chk_locations_source;
ALTER TABLE locations ADD CONSTRAINT chk_locations_source CHECK (
    source IN ('DEVICE_GPS', 'CAPTURE_GPS', 'EXIF', 'MANUAL_PIN', 'WARD_FALLBACK')
);

ALTER TABLE locations ADD CONSTRAINT chk_locations_detected_latitude CHECK (
    detected_latitude IS NULL OR detected_latitude BETWEEN -90 AND 90
);
ALTER TABLE locations ADD CONSTRAINT chk_locations_detected_longitude CHECK (
    detected_longitude IS NULL OR detected_longitude BETWEEN -180 AND 180
);
ALTER TABLE locations ADD CONSTRAINT chk_locations_accuracy CHECK (
    accuracy_m IS NULL OR accuracy_m >= 0
);
ALTER TABLE locations ADD CONSTRAINT chk_locations_submission_distance CHECK (
    submission_distance_m IS NULL OR submission_distance_m >= 0
);
