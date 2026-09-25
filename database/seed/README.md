# Dev/Test Seed Data

Files in this folder are **not** Flyway migrations and are **never** applied
automatically. They exist only to make local development and manual testing
convenient. Do not point Flyway's migration path at this folder.

## Usage

```bash
mysql -u jannet_user -p jannet_ai < database/seed/dev_test_seed.sql
```

Run this only after `database/migrations/` has been fully applied (V1–V15).

## Why this data isn't real

`dev_test_seed.sql` creates a placeholder Super Admin account with a
**known, non-production password hash placeholder** purely so a local
environment has at least one login to work with before the real
Authentication Module (Phase 4) and its OTP/registration flow exist.

**This file must never be run against staging or production.** The
placeholder password hash is not a real bcrypt/Argon2 hash — it is a string
literal marking the row as a seed artifact, and MUST be replaced by the real
Authentication Module bootstrap flow in Phase 4.

## Working dev logins for every role (`dev_login_accounts.sql`)

`dev_test_seed.sql` above only creates placeholder rows that cannot sign in.
To get one **working** login per role for local testing, run:

The script refuses to run unless you opt in explicitly (audit GAP-048), so it
cannot be applied to a shared database by accident:

```bash
( echo "SET @jannet_dev_seed_confirm='LOCAL_DEV_ONLY';"; cat database/seed/dev_login_accounts.sql ) \
  | mysql -u jannet_user -p jannet_ai
```

With Docker Compose (the MySQL service is named `mysql`; adjust if yours differs):

```bash
( echo "SET @jannet_dev_seed_confirm='LOCAL_DEV_ONLY';"; cat database/seed/dev_login_accounts.sql ) | docker compose exec -T mysql sh -c 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"'
```

All accounts use the shared **development-only** password `JanNet@2026` (a real BCrypt hash is stored). These accounts include ADMIN and SUPER_ADMIN, so the script must never be run against staging or production. Sign in with
the mobile number or the email:

| Role | Mobile | Email | Home screen |
|---|---|---|---|
| CITIZEN | 9000000001 | citizen@jannet.invalid | Citizen (ward W1) |
| GOVERNMENT_OFFICER | 9000000002 | officer@jannet.invalid | Officer (Public Works) |
| MAINTENANCE_TEAM | 9000000003 | maintenance@jannet.invalid | Officer (Public Works) |
| VERIFICATION_TEAM | 9000000004 | verifier@jannet.invalid | Verification |
| DEPARTMENT_HEAD | 9000000005 | depthead@jannet.invalid | Department Head (heads Public Works) |
| ADMIN | 9000000006 | admin@jannet.invalid | Admin (needs login OTP) |
| SUPER_ADMIN | 9000000007 | superadmin@jannet.invalid | Admin (needs login OTP) |

ADMIN and SUPER_ADMIN also need an OTP after the password (SRS 27.1). Locally, set
`OTP_DEV_LOG_CODE=true` (refused under the prod profile) and read the code from the
backend log, or configure real SMS. The file is safe to re-run; it resets these
accounts' passwords and unlocks them. **Never run it against staging or production.**
