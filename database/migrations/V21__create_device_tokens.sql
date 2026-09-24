-- V21__create_device_tokens.sql
-- Gap-backlog Patch 14/16 (Sep 2026 audit): FCM device token registry -
-- the schema gap NotificationService's own Javadoc named as the reason
-- PUSH was never actually dispatched ("no device-token storage exists
-- anywhere in this schema").

CREATE TABLE device_tokens (
    device_token_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT UNSIGNED NOT NULL,
    device_token    VARCHAR(255) NOT NULL,
    platform        VARCHAR(20) NOT NULL,
    last_seen_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_device_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON DELETE CASCADE,

    -- One row per physical token (re-registering the same token, e.g. on
    -- every app open, updates last_seen_at rather than accumulating
    -- duplicate rows - see DeviceTokenRepository).
    CONSTRAINT uk_device_tokens_token UNIQUE (device_token),

    CONSTRAINT chk_device_tokens_platform CHECK (
        platform IN ('ANDROID', 'IOS', 'WEB')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='FCM device tokens for real push delivery (Gap-backlog Patch 14/16)';

CREATE INDEX idx_device_tokens_user_id ON device_tokens (user_id);
