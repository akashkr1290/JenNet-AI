-- V19__create_complaint_ratings.sql
-- Gap-backlog Patch 11 (Sep 2026 audit): citizen rating of a resolved
-- complaint (1-5 stars + optional comment). One rating per complaint
-- (uk_complaint_ratings_complaint), matching the patch's own rule ("a
-- citizen should not be able to repeatedly rate the same complaint") at
-- the schema level, not just in application code.

CREATE TABLE complaint_ratings (
    rating_id     BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    complaint_id  BIGINT UNSIGNED NOT NULL,
    citizen_id    BIGINT UNSIGNED NOT NULL,
    rating        TINYINT UNSIGNED NOT NULL,
    comment       VARCHAR(500) NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_complaint_ratings_complaint
        FOREIGN KEY (complaint_id) REFERENCES complaints (complaint_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_complaint_ratings_citizen
        FOREIGN KEY (citizen_id) REFERENCES users (user_id)
        ON DELETE CASCADE,

    CONSTRAINT uk_complaint_ratings_complaint UNIQUE (complaint_id),
    CONSTRAINT chk_complaint_ratings_range CHECK (rating BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Citizen 1-5 star rating of a resolved/closed complaint (Gap-backlog Patch 11)';

CREATE INDEX idx_complaint_ratings_citizen_id ON complaint_ratings (citizen_id);
