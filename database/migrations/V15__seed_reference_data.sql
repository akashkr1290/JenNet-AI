-- V15__seed_reference_data.sql
-- Minimal reference data required for the platform to be usable at all,
-- as opposed to fake/sample test data (which lives in database/seed/ and
-- is NOT applied via Flyway — see that folder's README).
--
-- Only wards and departments are seeded here. routing_rules and settings
-- are deliberately NOT seeded in this migration: both tables require a
-- real users.user_id for created_by/updated_by (Business Rule, SRS 15.11:
-- "only Super Administrator may create or modify ... all configuration
-- changes are versioned and logged"), and no user account exists yet —
-- user creation is Phase 4 (Authentication Module)'s bootstrap concern, not
-- a migration's. Until an Admin configures real routing rules, the
-- Department Assignment Module's own documented fallback covers unmapped
-- categories: "unmapped categories default to a configurable 'General
-- Triage' department" (SRS 15.7 Exceptions) — which is why 'General
-- Triage' is seeded as a real department below.
--
-- Ward data below is illustrative placeholder data for a single pilot
-- jurisdiction (per SRS Assumptions: "initial deployment targets a single
-- municipal jurisdiction"). Replace with the real municipal ward list
-- before any non-local deployment.

INSERT INTO wards (name, code, is_active) VALUES
    ('Ward 1', 'W1', TRUE),
    ('Ward 2', 'W2', TRUE),
    ('Ward 3', 'W3', TRUE),
    ('Unassigned / Pending Ward Mapping', 'UNASSIGNED', TRUE);

-- Departments mirror the issue-category set locked in Phase 1
-- (ARCHITECTURE.md Section 5 / PROJECT_INTEGRATION.md Section 3) plus the
-- 'General Triage' fallback department required by SRS 15.7.
INSERT INTO departments (name, description, is_active) VALUES
    ('Public Works', 'Roads, potholes, illegal construction', TRUE),
    ('Water Supply', 'Water leakage and supply infrastructure', TRUE),
    ('Electrical', 'Street lighting and electrical infrastructure', TRUE),
    ('Sanitation', 'Garbage overflow and waste management', TRUE),
    ('General Triage', 'Fallback department for unmapped issue categories (SRS 15.7)', TRUE);
