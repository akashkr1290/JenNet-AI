-- V5__create_locations.sql
-- Resolved GPS/ward location for a complaint (SRS 19.5).
--
-- Each complaint gets exactly one Locations row (its resolved submission
-- location). Latitude/longitude use DECIMAL(9,6) as specified in the SRS's
-- Complaint Submission Form (17.2), which gives ~11cm precision — more than
-- sufficient for civic-issue geolocation.

CREATE TABLE locations (
    location_id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    latitude             DECIMAL(9,6)   NOT NULL,
    longitude            DECIMAL(9,6)   NOT NULL,
    ward_id              BIGINT UNSIGNED NULL,
    formatted_address    VARCHAR(300)   NULL,
    -- GPS Module business rule (SRS 15.5): "a complaint requires a location
    -- ... before it can proceed past AI Processing" and coordinates outside
    -- the municipal boundary are flagged. source captures how the location
    -- was obtained, and out_of_jurisdiction supports that flag without
    -- needing a live boundary check on every read.
    source               VARCHAR(20)    NOT NULL DEFAULT 'DEVICE_GPS',
    out_of_jurisdiction  BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_locations_ward
        FOREIGN KEY (ward_id) REFERENCES wards (ward_id)
        ON DELETE SET NULL,

    CONSTRAINT chk_locations_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT chk_locations_longitude CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT chk_locations_source CHECK (
        source IN ('DEVICE_GPS', 'EXIF', 'MANUAL_PIN')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Resolved GPS/ward location per complaint (SRS 19.5)';

CREATE INDEX idx_locations_ward_id ON locations (ward_id);
-- Supports simple bounding-box proximity queries used by Duplicate Detection
-- (SRS 15.6 / 21.5: "GPS proximity check ... within a configurable radius").
-- A full spatial index (SPATIAL POINT + ST_Distance_Sphere) is deferred
-- until the AI-service integration phase confirms the exact query pattern,
-- per ARCHITECTURE.md Section 7 (avoid unjustified complexity).
CREATE INDEX idx_locations_lat_lng ON locations (latitude, longitude);
