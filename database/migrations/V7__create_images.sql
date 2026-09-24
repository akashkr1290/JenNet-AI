-- V7__create_images.sql
-- Complaint media metadata (SRS 19.4). Binary image bytes are NEVER stored
-- here — S3 is the source of truth for file bytes (ARCHITECTURE.md Section
-- 2.4, PROJECT_INTEGRATION.md Section 5 "Media Storage Contract"). This
-- table stores only the object reference and metadata fields that contract
-- requires: object key/path, content type, size, uploader, upload
-- timestamp.

CREATE TABLE images (
    image_id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    complaint_id       BIGINT UNSIGNED NOT NULL,
    image_type          VARCHAR(20)   NOT NULL,
    -- Object key/path within the S3 bucket, NOT a public URL (buckets are
    -- not public per the media storage contract; access is via backend-
    -- issued pre-signed URLs at read time).
    storage_key           VARCHAR(500) NOT NULL,
    content_type            VARCHAR(50) NOT NULL,
    file_size_bytes           INT       NOT NULL,
    uploaded_by                BIGINT UNSIGNED NOT NULL,
    uploaded_at                 TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_images_complaint
        FOREIGN KEY (complaint_id) REFERENCES complaints (complaint_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_images_uploaded_by
        FOREIGN KEY (uploaded_by) REFERENCES users (user_id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_images_image_type CHECK (
        image_type IN ('BEFORE', 'AFTER')
    ),
    CONSTRAINT chk_images_content_type CHECK (
        content_type IN ('image/jpeg', 'image/png', 'image/webp')
    ),
    -- Citizen Module validation rule (SRS 15.1 / 17.2): photo must be under
    -- 10 MB.
    CONSTRAINT chk_images_file_size CHECK (
        file_size_bytes > 0 AND file_size_bytes <= 10485760
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Complaint image metadata; binary bytes live in S3 (SRS 19.4)';

CREATE INDEX idx_images_complaint_id ON images (complaint_id);
CREATE INDEX idx_images_uploaded_by ON images (uploaded_by);
