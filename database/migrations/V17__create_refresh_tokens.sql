-- V17__create_refresh_tokens.sql
-- Refresh token persistence for the Authentication Module (SRS 15.2 /
-- 27.5). Access tokens (JWT) are stateless and never stored; refresh
-- tokens must be persisted because the SRS mandates single-use rotation
-- with whole-family invalidation on reuse ("Sessions can be remotely
-- revoked by an Admin/Super Admin" — 27.5), which is impossible with a
-- purely stateless token.
--
-- DESIGN NOTE: only a SHA-256 hash of the refresh token is stored
-- (token_hash), never the raw token — the raw value exists only in the
-- HTTP response body/client storage, the same principle already applied to
-- users.password_hash and otp_verifications.otp_code_hash.
--
-- DESIGN NOTE: family_id groups every token descended from one original
-- login via rotation. On rotation, the old row is marked
-- replaced_by_token_id and revoked_at is set; if a token that is already
-- revoked/replaced is ever presented again, the entire family_id is
-- revoked (SRS 27.5 "reuse ... invalidates the entire token family").

CREATE TABLE refresh_tokens (
    refresh_token_id    BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id              BIGINT UNSIGNED NOT NULL,
    family_id            CHAR(36)        NOT NULL,
    token_hash           VARCHAR(255)    NOT NULL,
    issued_at            TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at           TIMESTAMP       NOT NULL,
    revoked_at           TIMESTAMP       NULL,
    replaced_by_token_id BIGINT UNSIGNED NULL,
    device_label         VARCHAR(255)    NULL,
    ip_address           VARCHAR(45)     NULL,

    UNIQUE KEY uq_refresh_tokens_token_hash (token_hash),

    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_refresh_tokens_replaced_by
        FOREIGN KEY (replaced_by_token_id) REFERENCES refresh_tokens (refresh_token_id)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Persisted refresh tokens enabling single-use rotation and remote session revocation (SRS 27.5, Phase 4)';

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);
