-- V13__create_settings.sql
-- Scoped configuration key/value store (SRS 19.11).
--
-- `key` is a reserved word in MySQL and must be backtick-quoted everywhere
-- it's referenced (including from application code / JPA @Column names in
-- later phases).

CREATE TABLE settings (
    setting_id      BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    scope             VARCHAR(20)   NOT NULL,
    scope_id            BIGINT UNSIGNED NULL,
    `key`                 VARCHAR(100) NOT NULL,
    value                   VARCHAR(200) NOT NULL,
    updated_by               BIGINT UNSIGNED NOT NULL,
    updated_at                 TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_settings_updated_by
        FOREIGN KEY (updated_by) REFERENCES users (user_id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_settings_scope CHECK (
        scope IN ('PLATFORM', 'DEPARTMENT', 'USER')
    ),
    -- A PLATFORM-scoped key has no scope_id; DEPARTMENT/USER-scoped keys
    -- must carry the owning department_id/user_id (enforced at application
    -- layer since a CHECK can't conditionally validate against two
    -- different foreign tables based on another column's value in a
    -- portable way).
    UNIQUE KEY uq_settings_scope_key (scope, scope_id, `key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Scoped platform/department/user configuration key-value store (SRS 19.11)';

CREATE INDEX idx_settings_scope ON settings (scope, scope_id);
