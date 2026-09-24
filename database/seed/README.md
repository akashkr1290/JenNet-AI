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
