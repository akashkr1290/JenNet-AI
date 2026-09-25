-- V26__create_ai_processing_jobs.sql
-- Audit GAP-010 (SRS 15.3 exception "retry queue, 3 retries, then Verification
-- Team"; NFR "submission acknowledged < 2 s"; risk table "queue-based
-- asynchronous processing"). AI classification used to run synchronously inside
-- POST /complaints with a single attempt; a failure left the complaint at
-- AI_PROCESSING forever. One row per complaint tracks the asynchronous AI run:
--
--   PENDING      waiting for (next) attempt at next_attempt_at
--   IN_PROGRESS  claimed by a worker until lease_expires_at (a crashed worker's
--                lease expires and the sweeper returns the job to PENDING)
--   DONE         AI processing finished (auto-verified, duplicate, parked for
--                manual review, or AI disabled - see last_error_code)
--   FAILED       retries exhausted or a non-retryable error; the Verification
--                Team was alerted and verifies the complaint manually
--
-- The claim is a single conditional UPDATE, so several backend instances can
-- share this table safely (SRS horizontal scaling). New table only - no
-- existing table is altered.

CREATE TABLE ai_processing_jobs (
    complaint_id      BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    state             VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    attempt_count     INT             NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_expires_at  TIMESTAMP       NULL,
    last_error_code   VARCHAR(60)     NULL,
    last_error        VARCHAR(500)    NULL,
    created_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_ai_processing_jobs_complaint
        FOREIGN KEY (complaint_id) REFERENCES complaints (complaint_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_ai_processing_jobs_state CHECK (
        state IN ('PENDING', 'IN_PROGRESS', 'DONE', 'FAILED')
    ),
    CONSTRAINT chk_ai_processing_jobs_attempts CHECK (attempt_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Asynchronous AI processing queue with retry state (audit GAP-010)';

CREATE INDEX idx_ai_processing_jobs_due ON ai_processing_jobs (state, next_attempt_at);
CREATE INDEX idx_ai_processing_jobs_lease ON ai_processing_jobs (state, lease_expires_at);
