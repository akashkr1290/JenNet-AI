-- V32__department_head_assignment_workflow.sql
-- Pilot workflow decisions (product owner, 2026-09-30):
--
--   Citizen -> AI (>= 50% accepted) / Verification Team (< 50%)
--     -> department (routing rule) -> Department Head assigns a Government
--        Officer -> ASSIGNED -> IN_PROGRESS -> RESOLVED (note + photo)
--     -> citizen confirms, or the complaint closes automatically after the
--        citizen confirmation period (default 3 days).
--
-- Schema is unchanged; this migration only aligns configuration data.
--
-- 1. AI confidence threshold 50 %.
--    * The Admin setting ai_confidence_threshold (85 on the pilot) -> 50.00.
--    * The column default for new routing rules -> 50.00.
--    * Active routing rules still at the old 85.00 default -> 50.00. A
--      category's routing rule OVERRIDES the Admin setting, and
--      RoutingRuleBootstrap seeded every category at 85.00 - so without this
--      the 85 % threshold would stay in force for every category.
--    Admins can raise any of them again (50-99) in Settings / Routing.
--
-- 2. Open Manhole -> Public Works. RoutingRuleBootstrap's default sent
--    OPEN_MANHOLE to 'General Triage'. Only a rule that the bootstrap created
--    and nobody changed (audit_logs ROUTING_RULE_SEEDED, still active, still
--    General Triage) is replaced - the rule history is kept: the old row is
--    deactivated and a new row effective today is added, owned by the same
--    user that owned the seeded rule. A rule an Admin created is never touched.
--
-- Rollback note: v0.1.7 runs unchanged against this data (same schema).

UPDATE settings
   SET value = '50.00'
 WHERE scope = 'PLATFORM'
   AND scope_id IS NULL
   AND `key` = 'ai_confidence_threshold'
   AND value <> '50.00';

ALTER TABLE routing_rules
    ALTER COLUMN ai_confidence_threshold SET DEFAULT 50.00;

UPDATE routing_rules
   SET ai_confidence_threshold = 50.00
 WHERE is_active = TRUE
   AND ai_confidence_threshold = 85.00;

-- Remember which seeded OPEN_MANHOLE rule to replace (MySQL cannot update a
-- table it reads from in the same statement's subquery).
CREATE TEMPORARY TABLE v32_manhole_rule AS
SELECT r.routing_rule_id, r.ai_confidence_threshold, r.duplicate_similarity_threshold,
       r.sla_hours, r.created_by
  FROM routing_rules r
  JOIN departments gt ON gt.department_id = r.department_id AND gt.name = 'General Triage'
 WHERE r.issue_category = 'OPEN_MANHOLE'
   AND r.is_active = TRUE
   AND r.effective_from < CURRENT_DATE
   AND EXISTS (SELECT 1 FROM audit_logs a
                WHERE a.action_type = 'ROUTING_RULE_SEEDED'
                  AND a.entity_type = 'ROUTING_RULE'
                  AND a.entity_id = r.routing_rule_id)
   AND EXISTS (SELECT 1 FROM departments pw WHERE pw.name = 'Public Works' AND pw.is_active = TRUE);

INSERT INTO routing_rules (issue_category, department_id, ai_confidence_threshold,
                           duplicate_similarity_threshold, sla_hours, effective_from,
                           is_active, created_by)
SELECT 'OPEN_MANHOLE', pw.department_id, m.ai_confidence_threshold, m.duplicate_similarity_threshold,
       m.sla_hours, CURRENT_DATE, TRUE, m.created_by
  FROM v32_manhole_rule m
  JOIN departments pw ON pw.name = 'Public Works' AND pw.is_active = TRUE;

UPDATE routing_rules r
  JOIN v32_manhole_rule m ON m.routing_rule_id = r.routing_rule_id
   SET r.is_active = FALSE;

DROP TEMPORARY TABLE v32_manhole_rule;
