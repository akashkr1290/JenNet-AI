-- V2__create_departments.sql
-- Government departments (SRS 19.3).
--
-- DEVIATION FROM SRS: the SRS's own Departments table lists
-- `jurisdiction_id INTEGER FK -> Locations.ward_id (nullable, or a
-- jurisdiction grouping table)`. That target is inconsistent (Locations'
-- primary key is location_id, not ward_id) and, per the SRS's own
-- Assumptions section, the pilot targets a single municipal jurisdiction —
-- so a per-department jurisdiction scope isn't needed yet. This column is
-- omitted; see ARCHITECTURE.md Section 9 for the tracked follow-up if/when
-- multi-jurisdiction support is exercised.
--
-- CIRCULAR REFERENCE NOTE: Departments.head_user_id references Users, and
-- Users.department_id references Departments. To avoid a chicken-and-egg
-- problem at schema-creation time, head_user_id is created here WITHOUT a
-- foreign key constraint; the constraint is added in
-- V4__add_departments_head_user_fk.sql once the users table exists.

CREATE TABLE departments (
    department_id   BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name             VARCHAR(100)   NOT NULL,
    description       VARCHAR(300)  NULL,
    head_user_id      BIGINT UNSIGNED NULL,   -- FK added in V4
    is_active         BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
                                      ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_departments_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Government departments responsible for complaint categories (SRS 19.3)';
