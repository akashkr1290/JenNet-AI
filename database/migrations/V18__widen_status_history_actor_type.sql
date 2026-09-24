-- V18__widen_status_history_actor_type.sql
-- Phase 6 (Complaint Module): widen status_history.actor_type's CHECK
-- constraint to add VERIFICATION_TEAM.
--
-- WHY: SRS 13.6 defines "Verification Team" as a distinct actor
-- responsible for manually reviewing AI_PROCESSING complaints (the
-- approved manual override for the pre-AI-service gap - AI Analysis
-- Module itself is Phase 7, not yet built). Role.VERIFICATION_TEAM
-- (V3__create_users.sql) has existed since Phase 2, but ActorType /
-- status_history.actor_type (V10__create_status_history.sql) was never
-- widened to match - V10 only carries SYSTEM, CITIZEN, OFFICER,
-- DEPARTMENT_HEAD, ADMIN. Every Verification Team override transition
-- this phase records (AI_PROCESSING -> VERIFIED/REJECTED/DUPLICATE) needs
-- its own honest actor_type value rather than being misrecorded as ADMIN
-- or OFFICER, so the audit trail (SRS 15.3: "every status change must be
-- recorded with actor") stays accurate for who actually acted.
--
-- Per this project's established convention (see V6/V10 header comments),
-- an already-shipped CHECK constraint is altered via DROP CHECK + ADD
-- CONSTRAINT rather than dropping/recreating the table.

-- Gap-backlog strict recheck (Sep 2026): DROP CHECK (MySQL-only) replaced by
-- the standard DROP CONSTRAINT, supported by MySQL >= 8.0.19 and by H2 - the
-- backend test profile runs these migrations on H2 (MODE=MySQL), where DROP
-- CHECK is a syntax error that stopped every Spring test context from
-- starting. Verified on MySQL 8.0.46 and H2 2.2.224 from a clean database.
-- Any pre-existing dev database that already applied the old V18 needs a
-- one-time `flyway repair` for the checksum change (production was never
-- deployed - see Gap-backlog Patch 24), following Phase 23's precedent of
-- fixing a genuine migration defect (V6) in place.
ALTER TABLE status_history
    DROP CONSTRAINT chk_status_history_actor_type;

ALTER TABLE status_history
    ADD CONSTRAINT chk_status_history_actor_type CHECK (
        actor_type IN ('SYSTEM', 'CITIZEN', 'OFFICER', 'DEPARTMENT_HEAD',
                        'ADMIN', 'VERIFICATION_TEAM')
    );
