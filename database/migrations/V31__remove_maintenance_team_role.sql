-- V31__remove_maintenance_team_role.sql
-- Pilot decision (2026-09-30): the Government Officer handles a complaint
-- end to end (In Progress -> Resolved with after-photo). MAINTENANCE_TEAM
-- never received any work (automatic and manual assignment only ever pick
-- GOVERNMENT_OFFICER), could see every complaint of every department and
-- got 403 on most actions, so the role is removed.
--
-- Existing MAINTENANCE_TEAM accounts are kept and become Government Officers
-- in the same department (department_id unchanged), so no account is lost.
-- From now on they are eligible for automatic assignment like any other
-- ACTIVE officer of their department.
UPDATE users SET role = 'GOVERNMENT_OFFICER' WHERE role = 'MAINTENANCE_TEAM';

ALTER TABLE users DROP CHECK chk_users_role;
ALTER TABLE users ADD CONSTRAINT chk_users_role CHECK (
    role IN ('CITIZEN', 'GOVERNMENT_OFFICER', 'DEPARTMENT_HEAD', 'ADMIN',
             'SUPER_ADMIN', 'VERIFICATION_TEAM')
);
