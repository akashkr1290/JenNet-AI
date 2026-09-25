# Credential rotation and secret handling (audit GAP-019)

The project archive `JanNet_AI.zip` that was audited on 2026-09-25 contained the real
local `.env` file. `.env` is (and was) listed in `.gitignore` and has never been
committed, so the Git history is clean. The file was distributed inside the ZIP,
though, so every value it held must be treated as disclosed.

No credential values appear in this document or in any patch. Rotation cannot be
automated from the repository, so it must be done by the team:

| Variable | Where it is used | How to rotate |
|---|---|---|
| `JWT_SECRET` | Backend token signing (`JwtService`) | Generate a new value (`openssl rand -hex 32`). Put it in `.env` locally and in SSM `/<project>/<env>/JWT_SECRET` for AWS. Rotating it invalidates every issued access token, so users simply log in again. |
| `DB_PASSWORD` | MySQL application user | `ALTER USER 'jannet_user'@'%' IDENTIFIED BY '<new>';`, then update `.env` or SSM. |
| `MYSQL_ROOT_PASSWORD` | Local Docker MySQL only | Recreate the local volume, or `ALTER USER 'root'@'%' ...`. RDS never uses it. |
| `BOOTSTRAP_SUPER_ADMIN_PASSWORD` (and the account it created) | First Super Admin (`SuperAdminBootstrap`) | Log in as that Super Admin and reset the password through the reset flow, or `UPDATE users SET password_hash=<bcrypt>`. Changing the env var alone does NOT change an existing account. |
| `BOOTSTRAP_SUPER_ADMIN_MOBILE` | Identifies that account | Not a secret by itself, but it was disclosed together with the password; rotate the password above. |
| `AI_SERVICE_API_KEY` | backend ↔ ai-service shared key | Generate a new value and set it identically for both services. |
| `LOCAL_STORAGE_SIGNING_SECRET` | Signed image URLs (local storage mode) | Generate a new value; existing image links expire within minutes anyway. |
| `SMTP_PASSWORD`, `SMS_PROVIDER_API_KEY`, `GEMINI_API_KEY`, Firebase JSON | External providers | These were empty in the audited file. Rotate at the provider if they were ever filled in. |

## Rules now enforced in code/config

* `.env` is ignored by Git (`.gitignore`); `.env.example` contains placeholders only (verified: no value equals a real one).
* JWT secrets, database credentials and bootstrap credentials are read from environment variables only. None are hard-coded.
* The `prod` profile refuses to start with the placeholder JWT secret (`app.jwt.allow-placeholder-secret: false`, `JwtService.init`).
* ai-service refuses to start with the public default API key unless `AI_SERVICE_ENV=local` (in which case it logs a warning and is bound to 127.0.0.1 by docker-compose).
* Production secrets come from AWS SSM Parameter Store (`deployment/scripts/ec2-user-data.sh.tpl`).

## Packaging deliverables without secrets

Use `scripts/package_source.sh`. It builds the archive from Git (`git archive`), so
`.env`, `.git/`, build output, IDE folders and other ignored files can never be
included:

```bash
scripts/package_source.sh JanNet_AI-source.zip
```
