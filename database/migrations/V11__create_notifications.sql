-- V11__create_notifications.sql
-- Delivered/queued notifications across channels (SRS 19.9, 20).

CREATE TABLE notifications (
    notification_id     BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id                BIGINT UNSIGNED NOT NULL,
    complaint_id              BIGINT UNSIGNED NULL,
    channel                     VARCHAR(20) NOT NULL,
    message                       VARCHAR(500) NOT NULL,
    delivery_status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    delivery_attempts                  INT NOT NULL DEFAULT 0,
    created_at                            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_notifications_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_notifications_complaint
        FOREIGN KEY (complaint_id) REFERENCES complaints (complaint_id)
        ON DELETE CASCADE,

    CONSTRAINT chk_notifications_channel CHECK (
        channel IN ('IN_APP', 'SMS', 'EMAIL', 'PUSH')
    ),
    CONSTRAINT chk_notifications_delivery_status CHECK (
        delivery_status IN ('PENDING', 'DELIVERED', 'FAILED')
    ),
    -- Notification Module business rule (SRS 15.13): "delivery failures are
    -- retried up to 3 times with exponential backoff before being logged
    -- [as failed]".
    CONSTRAINT chk_notifications_delivery_attempts CHECK (
        delivery_attempts BETWEEN 0 AND 3
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Notification delivery log across channels (SRS 19.9)';

CREATE INDEX idx_notifications_user_id ON notifications (user_id);
CREATE INDEX idx_notifications_complaint_id ON notifications (complaint_id);
CREATE INDEX idx_notifications_delivery_status ON notifications (delivery_status);
