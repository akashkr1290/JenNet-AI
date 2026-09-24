-- V14__create_routing_rules.sql
-- Category -> department routing rules with AI/duplicate thresholds and
-- SLA hours (SRS 17.4, "Admin — Routing Rule Form"; 15.7 Department
-- Assignment Module; 15.8/15.9 threshold usage).
--
-- This table wasn't listed in the SRS's Database Design section (17-22),
-- but 17.4 fully specifies its fields as a form, and the Department
-- Assignment / Priority / Budget modules all depend on this configuration
-- existing somewhere durable. Modeled as its own table rather than folded
-- into `settings` because it has real structure (FK to departments, several
-- typed/range-checked fields) that a generic key-value store would only
-- weakly enforce.
--
-- effective_from supports keeping a history of rule changes over time
-- (Admin screen 16.3 has a "View Change History" button) rather than only
-- ever holding the current rule; the application resolves the "active"
-- rule per category as the latest is_active row with
-- effective_from <= CURRENT_DATE.

CREATE TABLE routing_rules (
    routing_rule_id                    BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    issue_category                        VARCHAR(50) NOT NULL,
    department_id                            BIGINT UNSIGNED NOT NULL,
    ai_confidence_threshold                     DECIMAL(4,2) NOT NULL DEFAULT 85.00,
    duplicate_similarity_threshold                 DECIMAL(4,2) NOT NULL DEFAULT 80.00,
    sla_hours                                        INT NOT NULL,
    effective_from                                     DATE NOT NULL,
    is_active                                            BOOLEAN NOT NULL DEFAULT TRUE,
    created_by                                             BIGINT UNSIGNED NOT NULL,
    created_at                                               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_routing_rules_department
        FOREIGN KEY (department_id) REFERENCES departments (department_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_routing_rules_created_by
        FOREIGN KEY (created_by) REFERENCES users (user_id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_routing_rules_category CHECK (
        issue_category IN ('POTHOLE', 'GARBAGE_OVERFLOW', 'WATER_LEAKAGE',
                             'BROKEN_STREET_LIGHT', 'OPEN_MANHOLE',
                             'ILLEGAL_CONSTRUCTION', 'GENERAL')
    ),
    CONSTRAINT chk_routing_rules_ai_confidence CHECK (
        ai_confidence_threshold BETWEEN 50.00 AND 99.00
    ),
    CONSTRAINT chk_routing_rules_dup_similarity CHECK (
        duplicate_similarity_threshold BETWEEN 50.00 AND 99.00
    ),
    CONSTRAINT chk_routing_rules_sla_hours CHECK (
        sla_hours BETWEEN 1 AND 720
    ),

    UNIQUE KEY uq_routing_rules_category_effective (issue_category, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Issue-category to department routing rules with AI/duplicate thresholds and SLA (SRS 17.4)';

CREATE INDEX idx_routing_rules_category_active ON routing_rules (issue_category, is_active);
CREATE INDEX idx_routing_rules_department_id ON routing_rules (department_id);
