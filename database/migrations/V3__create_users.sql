-- V3__create_users.sql
-- Platform users across all roles (SRS 19.1).
--
-- DEVIATION FROM SRS (documented correction): the SRS's Users table lists
-- `ward_id INTEGER FK -> Locations.ward_id`. Locations' primary key is
-- location_id, not ward_id — Locations itself has a ward_id FK pointing at
-- Wards. Users.ward_id is corrected here to reference wards.ward_id
-- directly, which is the only table that actually has that key.
--
-- ROLE LIST REFINEMENT: Phase 1's PROJECT_INTEGRATION.md recorded a
-- placeholder 5-role set (CITIZEN, OFFICER, DEPARTMENT_HEAD, ADMIN,
-- SUPER_ADMIN) pending the real SRS. The now-supplied SRS Section 11 defines
-- eight roles, of which seven are real platform accounts (the eighth, "AI
-- Processing Engine", is a system actor with no user row). This migration
-- uses the SRS's seven-role set; PROJECT_INTEGRATION.md is updated in this
-- same phase to match (see that file's changelog entry).

CREATE TABLE users (
    user_id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    full_name           VARCHAR(100)   NOT NULL,
    mobile_number       VARCHAR(15)    NOT NULL,
    email               VARCHAR(150)   NULL,
    password_hash       VARCHAR(255)   NOT NULL,
    role                VARCHAR(30)    NOT NULL,
    department_id       BIGINT UNSIGNED NULL,
    ward_id             BIGINT UNSIGNED NULL,
    reputation_score    INT            NOT NULL DEFAULT 100,
    status              VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    mobile_verified_at  TIMESTAMP      NULL,
    email_verified_at   TIMESTAMP      NULL,
    failed_login_count  INT            NOT NULL DEFAULT 0,
    locked_until        TIMESTAMP      NULL,
    created_at          TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
                                        ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_users_mobile_number (mobile_number),
    UNIQUE KEY uq_users_email (email),

    CONSTRAINT fk_users_department
        FOREIGN KEY (department_id) REFERENCES departments (department_id)
        ON DELETE SET NULL,
    CONSTRAINT fk_users_ward
        FOREIGN KEY (ward_id) REFERENCES wards (ward_id)
        ON DELETE SET NULL,

    CONSTRAINT chk_users_role CHECK (
        role IN ('CITIZEN', 'GOVERNMENT_OFFICER', 'DEPARTMENT_HEAD', 'ADMIN',
                 'SUPER_ADMIN', 'VERIFICATION_TEAM', 'MAINTENANCE_TEAM')
    ),
    CONSTRAINT chk_users_status CHECK (
        status IN ('ACTIVE', 'SUSPENDED')
    ),
    CONSTRAINT chk_users_reputation_score CHECK (
        reputation_score BETWEEN 0 AND 1000
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='All platform user accounts across roles (SRS 19.1)';

CREATE INDEX idx_users_role ON users (role);
CREATE INDEX idx_users_department_id ON users (department_id);
CREATE INDEX idx_users_ward_id ON users (ward_id);
