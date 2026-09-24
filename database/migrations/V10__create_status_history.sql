-- V10__create_status_history.sql
-- Append-only audit trail of every complaint status transition (SRS 19.8).
--
-- ARCHITECTURE.md Section 4: "Every status transition is recorded
-- (who/when/from/to/reason) for audit and SLA tracking." This table is the
-- concrete implementation of that rule; new_status uses the same enum as
-- complaints.status (see V6 for the source-of-truth note on that list).

CREATE TABLE status_history (
    history_id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    complaint_id          BIGINT UNSIGNED NOT NULL,
    previous_status          VARCHAR(20) NULL,
    new_status                 VARCHAR(20) NOT NULL,
    actor_id                     BIGINT UNSIGNED NULL,
    actor_type                     VARCHAR(20) NOT NULL,
    reason                           VARCHAR(500) NULL,
    changed_at                         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_status_history_complaint
        FOREIGN KEY (complaint_id) REFERENCES complaints (complaint_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_status_history_actor
        FOREIGN KEY (actor_id) REFERENCES users (user_id)
        ON DELETE SET NULL,

    CONSTRAINT chk_status_history_new_status CHECK (
        new_status IN ('DRAFT', 'SUBMITTED', 'AI_PROCESSING', 'VERIFIED',
                         'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED',
                         'DUPLICATE', 'REJECTED', 'ESCALATED', 'REOPENED')
    ),
    CONSTRAINT chk_status_history_previous_status CHECK (
        previous_status IS NULL OR previous_status IN
        ('DRAFT', 'SUBMITTED', 'AI_PROCESSING', 'VERIFIED', 'ASSIGNED',
          'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'DUPLICATE', 'REJECTED',
          'ESCALATED', 'REOPENED')
    ),
    CONSTRAINT chk_status_history_actor_type CHECK (
        actor_type IN ('SYSTEM', 'CITIZEN', 'OFFICER', 'DEPARTMENT_HEAD',
                         'ADMIN')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Append-only complaint status transition audit trail (SRS 19.8)';

CREATE INDEX idx_status_history_complaint_id ON status_history (complaint_id);
CREATE INDEX idx_status_history_changed_at ON status_history (changed_at);
