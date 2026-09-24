-- V23__allow_ward_fallback_location_source.sql
-- Remaining-gaps item 3 (GPS / manual fallback): a complaint can now be
-- submitted with no coordinates at all when GPS is unavailable, as long as
-- the citizen selects a ward; the server stores an approximate point and
-- marks it with the new location source WARD_FALLBACK. Widens V5's CHECK
-- constraint. Separate DROP/ADD statements using the standard
-- DROP CONSTRAINT syntax so the same file runs on MySQL 8.0.19+ and on the
-- H2 (MODE=MySQL) database the backend test profile uses.
ALTER TABLE locations DROP CONSTRAINT chk_locations_source;
ALTER TABLE locations ADD CONSTRAINT chk_locations_source CHECK (
    source IN ('DEVICE_GPS', 'EXIF', 'MANUAL_PIN', 'WARD_FALLBACK')
);
