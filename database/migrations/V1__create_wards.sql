-- V1__create_wards.sql
-- Wards / administrative zones used for location-based routing and reporting.
--
-- SOURCE: SRS section 19.5 (Locations) references "Locations (M) -> (1) Wards"
-- but the SRS's own Database Design section (17-22) never defines a dedicated
-- Wards table. This migration creates it, since Locations.ward_id and
-- Users.ward_id both require a real target table.
--
-- ASSUMPTION (documented per PHASE_HANDOFF.md instruction to log assumptions):
-- pilot deployment targets a single municipal jurisdiction (SRS section
-- "Assumptions"), so no separate jurisdiction/municipality table is created
-- yet. If multi-jurisdiction support is exercised later, add a
-- `jurisdiction_id` column here (see ARCHITECTURE.md Section 9 for the
-- open item tracking this).

CREATE TABLE wards (
    ward_id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name              VARCHAR(150)    NOT NULL,
    code              VARCHAR(30)     NULL,
    -- Reverse-geocoding / Admin ward-boundary editor (SRS 16.3, "Admin —
    -- Routing & Threshold Configuration" screen) needs a boundary shape.
    -- Stored as GeoJSON text for now; a real GIS/spatial type is deferred
    -- until a phase that needs spatial queries (e.g. ST_Contains) states
    -- that requirement explicitly (see ARCHITECTURE.md Section 7,
    -- avoid-overengineering rule).
    boundary_geojson  JSON            NULL,
    is_active         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
                                       ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_wards_name (name),
    UNIQUE KEY uq_wards_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Administrative wards/zones for location-based routing (SRS 19.5 / ER summary)';
