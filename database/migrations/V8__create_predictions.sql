-- V8__create_predictions.sql
-- AI classification/severity output per complaint (SRS 19.6).
--
-- ADDITION vs. SRS DB Design table: the SRS's own Functional Requirements
-- (15.8, Priority Prediction Module) says the module outputs "severity
-- level, numeric priority score" — but the SRS's Predictions table (19.6)
-- only has predicted_severity, no numeric score column. priority_score is
-- added here to actually hold that documented output; this is an internal
-- SRS inconsistency being resolved, not a deviation from stated
-- requirements.
--
-- raw_model_output uses MySQL JSON (SRS specifies Postgres JSONB — see the
-- top-level tech-stack note recorded in PROJECT_PROGRESS.md/ARCHITECTURE.md
-- for why this project uses MySQL instead).
--
-- No trained model exists yet (ARCHITECTURE.md Section 5) — this table only
-- provides the storage shape; it is not populated until Phase 7+.
--
-- CARDINALITY NOTE: the SRS's ER summary (19.12) describes Complaints ->
-- Predictions as 1:1, but the AI Analysis Module's own exception handling
-- (SRS 15.3: "if AI processing fails ... retry queue ... after 3 failed
-- retries") implies more than one prediction attempt can be recorded per
-- complaint. complaint_id is therefore left non-unique here so each attempt
-- can be logged for audit/retraining; the application should treat the row
-- with the latest created_at as authoritative. Tighten to a UNIQUE
-- constraint in a later phase if the AI-service integration ends up
-- overwriting rather than appending.

CREATE TABLE predictions (
    prediction_id       BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    complaint_id          BIGINT UNSIGNED NOT NULL,
    ai_confidence            DECIMAL(5,2) NOT NULL,
    model_version              VARCHAR(30) NOT NULL,
    predicted_severity           VARCHAR(10) NULL,
    priority_score                 DECIMAL(6,2) NULL,
    duplicate_flag                   BOOLEAN NOT NULL DEFAULT FALSE,
    raw_model_output                   JSON  NULL,
    created_at                           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_predictions_complaint
        FOREIGN KEY (complaint_id) REFERENCES complaints (complaint_id)
        ON DELETE CASCADE,

    CONSTRAINT chk_predictions_ai_confidence CHECK (
        ai_confidence BETWEEN 0 AND 100
    ),
    CONSTRAINT chk_predictions_severity CHECK (
        predicted_severity IS NULL
        OR predicted_severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='AI classification/severity output per complaint (SRS 19.6)';

CREATE INDEX idx_predictions_complaint_id ON predictions (complaint_id);
