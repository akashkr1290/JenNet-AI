-- dev_login_accounts.sql
-- LOCAL DEVELOPMENT / MANUAL TESTING ONLY. Never run against staging or
-- production. Not a Flyway migration - see this folder's README.md.
--
-- Creates one working login per platform role so every home screen can be
-- tried locally. All accounts share the dev password:
--
--     JanNet@2026
--
-- password_hash is a real BCrypt ($2a$, cost 10) hash of that password - the
-- same format SecurityConfig's BCryptPasswordEncoder produces - so these
-- accounts can sign in through POST /api/v1/auth/login.
--
-- ADMIN and SUPER_ADMIN always need an SMS OTP after the password (SRS 27.1,
-- RoleConstants.requiresMfa). Locally, either configure SMS or set
-- OTP_DEV_LOG_CODE=true (dev profile only) and read the code from the
-- backend log.
--
-- Safe to re-run: existing rows with these mobile numbers get their
-- password, role, status and assignments reset.
-- Run after all Flyway migrations (it needs the V15 wards/departments).

-- Audit GAP-048: refuse to run unless the operator explicitly opts in for a
-- LOCAL development database. Without the opt-in the SELECT below raises
-- "ERROR 1242 Subquery returns more than 1 row" and the mysql client stops
-- before any account is created. Run it as:
--
--   ( echo "SET @jannet_dev_seed_confirm='LOCAL_DEV_ONLY';"; cat database/seed/dev_login_accounts.sql ) \
--     | mysql -u jannet_user -p jannet_ai
SELECT IF(@jannet_dev_seed_confirm = 'LOCAL_DEV_ONLY', 'confirmed',
          (SELECT 'refused: set @jannet_dev_seed_confirm' UNION ALL SELECT 'refused'))
  INTO @jannet_dev_seed_guard;

SET @dev_hash = '$2a$10$Pa5QPuZHsFVLmDfZOfa/..cZeKcLZ2YAcWZsnnlMcjd6fJu70NCfK';
SET @ward_w1 = (SELECT ward_id FROM wards WHERE code = 'W1');
SET @dept_pw = (SELECT department_id FROM departments WHERE name = 'Public Works');

INSERT INTO users (full_name, mobile_number, email, password_hash, role, ward_id, department_id,
                   status, failed_login_count, mobile_verified_at)
VALUES
    ('Dev Citizen',           '9000000001', 'citizen@jannet.invalid',      @dev_hash, 'CITIZEN',            @ward_w1, NULL,     'ACTIVE', 0, CURRENT_TIMESTAMP),
    ('Dev Field Officer',     '9000000002', 'officer@jannet.invalid',      @dev_hash, 'GOVERNMENT_OFFICER', NULL,     @dept_pw, 'ACTIVE', 0, CURRENT_TIMESTAMP),
    ('Dev Maintenance Team',  '9000000003', 'maintenance@jannet.invalid',  @dev_hash, 'MAINTENANCE_TEAM',   NULL,     @dept_pw, 'ACTIVE', 0, CURRENT_TIMESTAMP),
    ('Dev Verifier',          '9000000004', 'verifier@jannet.invalid',     @dev_hash, 'VERIFICATION_TEAM',  NULL,     NULL,     'ACTIVE', 0, CURRENT_TIMESTAMP),
    ('Dev Department Head',   '9000000005', 'depthead@jannet.invalid',     @dev_hash, 'DEPARTMENT_HEAD',    NULL,     @dept_pw, 'ACTIVE', 0, CURRENT_TIMESTAMP),
    ('Dev Admin',             '9000000006', 'admin@jannet.invalid',        @dev_hash, 'ADMIN',              NULL,     NULL,     'ACTIVE', 0, CURRENT_TIMESTAMP),
    ('Dev Super Admin',       '9000000007', 'superadmin@jannet.invalid',   @dev_hash, 'SUPER_ADMIN',        NULL,     NULL,     'ACTIVE', 0, CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE
    password_hash      = VALUES(password_hash),
    role               = VALUES(role),
    ward_id            = VALUES(ward_id),
    department_id      = VALUES(department_id),
    status             = 'ACTIVE',
    failed_login_count = 0,
    locked_until       = NULL,
    mobile_verified_at = COALESCE(mobile_verified_at, CURRENT_TIMESTAMP);

-- The department head heads Public Works (departments.head_user_id, V4).
UPDATE departments
SET head_user_id = (SELECT user_id FROM users WHERE mobile_number = '9000000005')
WHERE department_id = @dept_pw;
