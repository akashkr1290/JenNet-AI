-- V12__create_audit_logs.sql
-- Append-only administrative/configuration audit trail (SRS 19.10).
--
-- Security Section 27.4: "Audit logs are append-only and are not exposed
-- for deletion through any application interface." No DELETE-granting
-- application role should ever be given DELETE on this table; enforced at
-- the application/DB-user-grant layer in a later phase (Phase 4 — Auth),
-- noted here so it isn't forgotten.

CREATE TABLE audit_logs (
    log_id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    actor_id             BIGINT UNSIGNED NULL,
    action_type             VARCHAR(50) NOT NULL,
    entity_type                VARCHAR(50) NOT NULL,
    entity_id                     BIGINT UNSIGNED NOT NULL,
    details                          JSON NULL,
    created_at                          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_audit_logs_actor
        FOREIGN KEY (actor_id) REFERENCES users (user_id)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Append-only administrative/configuration audit trail (SRS 19.10)';

CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_logs_actor_id ON audit_logs (actor_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at);
