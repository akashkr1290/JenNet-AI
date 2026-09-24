-- V20__create_complaint_appeals.sql
-- Gap-backlog Patch 12 (Sep 2026 audit): a citizen appeal of a REJECTED
-- complaint's decision. Deliberately its own table rather than reusing
-- status_history: an appeal has its own lifecycle (PENDING -> APPROVED/
-- DENIED) and reviewer fields that don't fit status_history's
-- previous/new-status shape.

CREATE TABLE complaint_appeals (
    appeal_id     BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    complaint_id  BIGINT UNSIGNED NOT NULL,
    citizen_id    BIGINT UNSIGNED NOT NULL,
    reason        VARCHAR(1000) NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reviewed_by   BIGINT UNSIGNED NULL,
    reviewed_at   TIMESTAMP NULL,
    review_note   VARCHAR(1000) NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_complaint_appeals_complaint
        FOREIGN KEY (complaint_id) REFERENCES complaints (complaint_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_complaint_appeals_citizen
        FOREIGN KEY (citizen_id) REFERENCES users (user_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_complaint_appeals_reviewer
        FOREIGN KEY (reviewed_by) REFERENCES users (user_id)
        ON DELETE SET NULL,

    CONSTRAINT chk_complaint_appeals_status CHECK (
        status IN ('PENDING', 'APPROVED', 'DENIED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Citizen appeal of a REJECTED complaint decision (Gap-backlog Patch 12)';

CREATE INDEX idx_complaint_appeals_complaint_id ON complaint_appeals (complaint_id);
CREATE INDEX idx_complaint_appeals_status ON complaint_appeals (status);

-- One PENDING appeal at a time per complaint. pending_complaint_id is set
-- by ComplaintAppealService to complaint_id while the appeal is PENDING and
-- cleared to NULL once reviewed; a UNIQUE index permits any number of NULLs
-- in both MySQL and H2, so this enforces the rule at the schema level
-- portably. (An earlier draft of this unreleased migration used a MySQL-only
-- GENERATED ... STORED column with IF(), which H2's MySQL mode - used by the
-- backend test profile, which runs these same migrations - does not accept.)
ALTER TABLE complaint_appeals ADD COLUMN pending_complaint_id BIGINT UNSIGNED NULL;
CREATE UNIQUE INDEX uk_complaint_appeals_one_pending ON complaint_appeals (pending_complaint_id);
