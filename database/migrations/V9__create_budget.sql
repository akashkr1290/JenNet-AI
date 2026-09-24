-- V9__create_budget.sql
-- Estimated repair cost range and resolution time per complaint (SRS 19.7).
--
-- Budget Prediction Module business rule (SRS 15.9): "estimates are
-- presented as a range (minimum-maximum), never a single fixed figure" —
-- enforced here with a CHECK that max >= min. confidence_level captures the
-- module's documented "confidence indicator" output (cold-start categories
-- return a low-confidence/preliminary flag per SRS 21.8).
--
-- Out of scope per SRS Section "Out of Scope": these are planning estimates
-- only, never a real financial transaction — no payment/ledger fields here.

CREATE TABLE budget (
    budget_id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    complaint_id               BIGINT UNSIGNED NOT NULL,
    estimated_cost_min           DECIMAL(10,2) NOT NULL,
    estimated_cost_max             DECIMAL(10,2) NOT NULL,
    estimated_resolution_days        INT         NOT NULL,
    confidence_level                   VARCHAR(20) NOT NULL DEFAULT 'PRELIMINARY',
    approved_by                          BIGINT UNSIGNED NULL,
    created_at                             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_budget_complaint
        FOREIGN KEY (complaint_id) REFERENCES complaints (complaint_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_budget_approved_by
        FOREIGN KEY (approved_by) REFERENCES users (user_id)
        ON DELETE SET NULL,

    CONSTRAINT chk_budget_cost_range CHECK (
        estimated_cost_min >= 0 AND estimated_cost_max >= estimated_cost_min
    ),
    CONSTRAINT chk_budget_resolution_days CHECK (
        estimated_resolution_days >= 0
    ),
    CONSTRAINT chk_budget_confidence_level CHECK (
        confidence_level IN ('PRELIMINARY', 'STANDARD', 'HIGH')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Estimated repair cost range and resolution time (SRS 19.7)';

CREATE INDEX idx_budget_complaint_id ON budget (complaint_id);
