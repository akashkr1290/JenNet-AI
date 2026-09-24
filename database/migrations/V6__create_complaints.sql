-- V6__create_complaints.sql
-- Core complaint record and lifecycle state (SRS 19.2).
--
-- STATUS VALUES: use the 12-value enum LOCKED in Phase 1
-- (PROJECT_INTEGRATION.md Section 3 / ARCHITECTURE.md Section 4), not the
-- SRS's own 11-value list (Table 11), because Phase 1's explicit rule is
-- that later phases match the already-agreed values rather than inventing
-- new ones. The one substantive difference is naming: the SRS calls the
-- duplicate state "Duplicate Found"; the locked enum calls it DUPLICATE.
-- The locked value is used as the canonical status string.
--
-- ESCALATED / REOPENED AS STATUS VALUES: the SRS describes escalation as
-- "an annotation (not a terminal state) ... the underlying status continues
-- to progress normally once escalated" (Table 11), and describes reopen as
-- resetting status to In Progress with a reopen flag (API table, 20.2). That
-- conflicts with treating ESCALATED/REOPENED as standalone status values.
-- Rather than dropping ESCALATED/REOPENED from the locked enum (which
-- Phase 1 said not to do without recording the contradiction — see
-- ARCHITECTURE.md Section 9), this migration keeps both as valid `status`
-- values for flexibility AND adds dedicated is_escalated/is_reopened flags
-- + timestamps so the SRS's actual behavior (annotate the current status
-- rather than replace it) is directly representable. Phase 6 (Complaint
-- Module, which owns the real transition table) should pick one approach
-- and can drop the unused column set at that time.

CREATE TABLE complaints (
    complaint_id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    reference_number       VARCHAR(20)    NOT NULL,
    citizen_id              BIGINT UNSIGNED NOT NULL,
    category                 VARCHAR(50)   NOT NULL,
    description               VARCHAR(500) NULL,
    location_id               BIGINT UNSIGNED NULL,
    department_id             BIGINT UNSIGNED NULL,
    assigned_officer_id       BIGINT UNSIGNED NULL,
    status                    VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    severity                  VARCHAR(10)   NULL,
    parent_complaint_id       BIGINT UNSIGNED NULL,
    corroboration_count       INT           NOT NULL DEFAULT 1,
    is_escalated              BOOLEAN       NOT NULL DEFAULT FALSE,
    escalated_at               TIMESTAMP    NULL,
    is_reopened                BOOLEAN      NOT NULL DEFAULT FALSE,
    reopened_at                 TIMESTAMP   NULL,
    rejection_reason_code        VARCHAR(50) NULL,
    created_at                    TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                                              ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_complaints_reference_number (reference_number),

    CONSTRAINT fk_complaints_citizen
        FOREIGN KEY (citizen_id) REFERENCES users (user_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_complaints_location
        FOREIGN KEY (location_id) REFERENCES locations (location_id)
        ON DELETE SET NULL,
    CONSTRAINT fk_complaints_department
        FOREIGN KEY (department_id) REFERENCES departments (department_id)
        ON DELETE SET NULL,
    CONSTRAINT fk_complaints_assigned_officer
        FOREIGN KEY (assigned_officer_id) REFERENCES users (user_id)
        ON DELETE SET NULL,
    CONSTRAINT fk_complaints_parent_complaint
        FOREIGN KEY (parent_complaint_id) REFERENCES complaints (complaint_id)
        ON DELETE SET NULL,

    CONSTRAINT chk_complaints_category CHECK (
        category IN ('POTHOLE', 'GARBAGE_OVERFLOW', 'WATER_LEAKAGE',
                      'BROKEN_STREET_LIGHT', 'OPEN_MANHOLE',
                      'ILLEGAL_CONSTRUCTION', 'GENERAL')
    ),
    CONSTRAINT chk_complaints_status CHECK (
        status IN ('DRAFT', 'SUBMITTED', 'AI_PROCESSING', 'VERIFIED',
                    'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED',
                    'DUPLICATE', 'REJECTED', 'ESCALATED', 'REOPENED')
    ),
    CONSTRAINT chk_complaints_severity CHECK (
        severity IS NULL OR severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    ),
    CONSTRAINT chk_complaints_corroboration_count CHECK (
        corroboration_count >= 1
    )
    -- NOTE (Phase 23 — first real execution against MySQL 8 surfaced this):
    -- a `chk_complaints_not_self_parent CHECK (parent_complaint_id IS NULL
    -- OR parent_complaint_id <> complaint_id)` constraint previously lived
    -- here. MySQL 8.0 rejects it outright (ERROR 3823: "Column
    -- 'parent_complaint_id' cannot be used in a check constraint ... needed
    -- in a foreign key constraint ... referential action") because InnoDB
    -- does not allow a column that carries a FK referential action (this
    -- table's own `fk_complaints_parent_complaint ... ON DELETE SET NULL`,
    -- immediately above) to also appear in a CHECK constraint — this is a
    -- real MySQL/InnoDB limitation, not a modeling mistake, and every prior
    -- phase's static-only validation (brace/paren balance, manual SQL
    -- review) could not have caught it since it only manifests against a
    -- real server. Removed rather than reworked into a trigger, because the
    -- exact same rule is already enforced at the application layer —
    -- `ComplaintService`'s DUPLICATE-decision branch explicitly rejects
    -- `parentComplaintId.equals(complaintId)` with an
    -- `InvalidStateTransitionException` before this table is ever written
    -- to — so the CHECK was pure defense-in-depth, not the only guard.
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Core civic complaint record and lifecycle status (SRS 19.2)';

CREATE INDEX idx_complaints_citizen_id ON complaints (citizen_id);
CREATE INDEX idx_complaints_department_id ON complaints (department_id);
CREATE INDEX idx_complaints_assigned_officer_id ON complaints (assigned_officer_id);
CREATE INDEX idx_complaints_status ON complaints (status);
CREATE INDEX idx_complaints_severity ON complaints (severity);
CREATE INDEX idx_complaints_parent_complaint_id ON complaints (parent_complaint_id);
CREATE INDEX idx_complaints_created_at ON complaints (created_at);
-- Common dashboard/queue filter combination (SRS 15.10, 16.2 Officer Queue):
-- "sortable/filterable table (status, severity, SLA countdown)" scoped by
-- department.
CREATE INDEX idx_complaints_department_status ON complaints (department_id, status);
