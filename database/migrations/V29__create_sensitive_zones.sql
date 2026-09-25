-- V29__create_sensitive_zones.sql
-- Audit GAP-033 (SRS 15.8 Features: "location-sensitivity weighting
-- (proximity to schools, hospitals, high-traffic roads)").
--
-- The project has no POI data source (no OpenStreetMap or municipal
-- school/hospital/road layer is integrated). Instead of fabricating one, an
-- Admin records the sensitive places they know about as circles: a centre
-- point and a radius. A complaint whose location falls inside an active zone
-- sends the matching flag (near_school / near_hospital / high_traffic_road) to
-- the ai-service priority model, which raises severity by one level (existing
-- rule in ai-service/app/services/priority_service.py). Importing a real POI
-- layer is documented as an external data requirement.

CREATE TABLE sensitive_zones (
    zone_id        BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(150)    NOT NULL,
    zone_type      VARCHAR(30)     NOT NULL,
    latitude       DECIMAL(9,6)    NOT NULL,
    longitude      DECIMAL(9,6)    NOT NULL,
    radius_meters  INT             NOT NULL,
    is_active      BOOLEAN         NOT NULL DEFAULT TRUE,
    created_by     BIGINT UNSIGNED NOT NULL,
    created_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_sensitive_zones_created_by
        FOREIGN KEY (created_by) REFERENCES users (user_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_sensitive_zones_type CHECK (zone_type IN ('SCHOOL', 'HOSPITAL', 'HIGH_TRAFFIC_ROAD')),
    CONSTRAINT chk_sensitive_zones_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT chk_sensitive_zones_longitude CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT chk_sensitive_zones_radius CHECK (radius_meters BETWEEN 10 AND 5000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Admin-recorded schools/hospitals/high-traffic roads for SRS 15.8 location weighting (audit GAP-033)';

CREATE INDEX idx_sensitive_zones_active ON sensitive_zones (is_active);
