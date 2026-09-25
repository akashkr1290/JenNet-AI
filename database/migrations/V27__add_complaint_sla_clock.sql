-- V27__add_complaint_sla_clock.sql
-- Audit GAP-027 (SRS 14.3: escalate after N hours in Assigned/In Progress
-- "without status change"; SRS 15.11: SLA changes are not retroactive).
--
-- Before: the escalation sweep measured from updated_at, which ANY write
-- resets (escalation flag, classification override, corroboration from a
-- merged duplicate), while dashboards showed a due time computed from
-- created_at; changing an SLA setting instantly re-timed every open complaint.
--
-- Now the SLA clock is stored on the complaint when it ENTERS Assigned or In
-- Progress, from the SLA hours in force at that moment:
--   sla_started_at  when the clock (re)started (status change into ASSIGNED/IN_PROGRESS)
--   sla_hours       SLA hours in force then (kept - later setting changes do not apply retroactively)
--   sla_warning_at  80 % point (SRS 15.13 officer warning)
--   sla_due_at      breach point - escalation and every displayed due time use this
-- All NULL until a clock starts. Open complaints that predate this migration
-- are given a clock by the first escalation sweep (EscalationSchedulerService),
-- started at their updated_at - the same instant the old logic measured from -
-- so their timing does not jump.

ALTER TABLE complaints
    ADD COLUMN sla_started_at TIMESTAMP NULL AFTER reopened_at,
    ADD COLUMN sla_hours      INT       NULL AFTER sla_started_at,
    ADD COLUMN sla_warning_at TIMESTAMP NULL AFTER sla_hours,
    ADD COLUMN sla_due_at     TIMESTAMP NULL AFTER sla_warning_at;

ALTER TABLE complaints
    ADD CONSTRAINT chk_complaints_sla_hours CHECK (sla_hours IS NULL OR sla_hours BETWEEN 1 AND 8760);

CREATE INDEX idx_complaints_status_sla_due ON complaints (status, sla_due_at);
CREATE INDEX idx_complaints_status_sla_warning ON complaints (status, sla_warning_at);
