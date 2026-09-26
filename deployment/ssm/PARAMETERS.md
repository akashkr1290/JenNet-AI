# SSM Parameter Store — required parameters (Phase 22)

None of these are created by Terraform (see `../aws/terraform/rds.tf`'s
header comment for why secret *values* are deliberately kept out of
`.tf`/`.tfvars`/`terraform.tfstate` entirely). An operator with real AWS
credentials must create each of these as a `SecureString` parameter
under the path `/jannet-ai/<environment>/` (e.g.
`/jannet-ai/pilot/JWT_SECRET`) **before** the EC2 instance's first boot —
`scripts/ec2-user-data.sh.tpl` reads them at that path and will proceed
with an empty value (matching `.env.example`'s own safe-empty-default
convention) for anything not yet created, rather than failing the boot.

```bash
aws ssm put-parameter \
  --name "/jannet-ai/pilot/JWT_SECRET" \
  --type SecureString \
  --value "$(openssl rand -base64 48)"
```

Repeat for each name below, substituting a real generated/obtained value
for each. **Never** put a real value in this file, in a commit, or in a
Terraform `.tfvars` file.

| Parameter name | Corresponds to (`.env.example`) | Notes |
|---|---|---|
| `JWT_SECRET` | `JWT_SECRET` | 256-bit+ random value — `openssl rand -base64 48`, never reused from any lower environment. |
| `AI_SERVICE_API_KEY` | `AI_SERVICE_API_KEY` | Shared secret between backend and ai-service containers — same value must be used for both (bootstrap script writes it once into the single `.env` both containers read, so this is automatic as long as this one parameter is set correctly). |
| `BOOTSTRAP_SUPER_ADMIN_MOBILE` | `BOOTSTRAP_SUPER_ADMIN_MOBILE` | Real mobile number for the first ADMIN/SUPER_ADMIN account. Leave unset to skip bootstrap (no first-login account created) if a different provisioning path is preferred. |
| `BOOTSTRAP_SUPER_ADMIN_PASSWORD` | `BOOTSTRAP_SUPER_ADMIN_PASSWORD` | Must satisfy SRS password-complexity rule (8+ chars, mixed case, number, special character) — `AuthService` rejects a non-compliant bootstrap password at startup. Rotate immediately after first login. |
| `GEMINI_API_KEY` | `GEMINI_API_KEY` | Leave unset to run ai-service in fallback-only mode (no external Gemini calls) — matches this project's existing safe-default behavior. |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | `SMTP_USERNAME` / `SMTP_PASSWORD` | Only needed if `NOTIFICATION_EMAIL_ENABLED=true` is also set (see below). |
| `SMS_PROVIDER_API_KEY` | `SMS_PROVIDER_API_KEY` | Only needed if `NOTIFICATION_SMS_ENABLED=true` is also set. |
| `GHCR_PULL_TOKEN` | *(new — deployment-only)* | A GitHub Personal Access Token with `read:packages` scope, ONLY needed if the repository's GHCR packages are private (GitHub's default for a private repo). Public GHCR packages need no login at all — leave this parameter unset in that case. |
| `ACME_EMAIL` | *(new — deployment-only)* | Contact email Let's Encrypt associates with the TLS certificate for renewal/expiry notices. |

## Plain settings (audit fix Phase 07, GAP-018)

`ec2-user-data.sh.tpl` now writes every production-relevant setting. The
non-secret ones below are read from **String** parameters under the same
path (the instance role may read everything under `/jannet-ai/<environment>/`).
A parameter that does not exist writes nothing, so the application default
applies (shown in the last column). The backend refuses to start under the
`prod` profile if a required value is missing or unsafe
(`ProductionConfigurationValidator`), with the variable name in the log.

| Parameter name | When needed | Default when unset |
|---|---|---|
| `NOTIFICATION_EMAIL_ENABLED` / `NOTIFICATION_SMS_ENABLED` / `NOTIFICATION_PUSH_ENABLED` | `true` once the provider account exists | `false` |
| `NOTIFICATION_EMAIL_FROM`, `SMTP_HOST`, `SMTP_PORT` | e-mail enabled (startup fails without a real host / sender) | `no-reply@jannetai.local`, `localhost`, `587` |
| `SMS_PROVIDER_URL` and the `SMS_*` adapter settings (`SMS_PROVIDER`, `SMS_PROVIDER_REQUEST_FORMAT`, `SMS_PROVIDER_AUTH_SCHEME`, `SMS_PROVIDER_AUTH_HEADER`, `SMS_PROVIDER_AUTH_QUERY_PARAM`, `SMS_PROVIDER_BASIC_USERNAME`, `SMS_PROVIDER_NUMBER_FORMAT`, `SMS_PROVIDER_TO_FIELD`, `SMS_PROVIDER_MESSAGE_FIELD`, `SMS_PROVIDER_SENDER_FIELD`, `SMS_SENDER_ID`, `SMS_DLT_*`, `SMS_OTP_DLT_TEMPLATE_ID`, `SMS_NOTIFICATION_DLT_TEMPLATE_ID`, `SMS_PROVIDER_SUCCESS_PATTERN`, `OTP_SMS_TEMPLATE`) | SMS enabled - see `docs/SMS_PROVIDER_CONFIGURATION.md` | adapter defaults |
| `GEMINI_MODEL_NAME` | to pin a Gemini model | ai-service default |
| `GEO_MIN_LAT`, `GEO_MAX_LAT`, `GEO_MIN_LNG`, `GEO_MAX_LNG` | always for a real municipality (startup warns while unset) | all-India placeholder box |
| `COMPLAINT_PROFANITY_WORDS`, `COMPLAINT_PROFANITY_ACTION`, `REPORTS_MIN_COMPLAINTS` | product-owner decisions (docs/SRS_PHASE06_DECISIONS.md) | off / `REJECT` / `1` |
| `CORS_ALLOWED_ORIGINS` | only when `domain_name` is empty (degraded HTTP mode) | with a domain: `https://<domain_name>` is written automatically |
| `LOCAL_STORAGE_SIGNING_SECRET` (SecureString) | only if `STORAGE_PROVIDER` is switched back to `local` | - |

Written without a parameter: `STORAGE_PROVIDER=s3` and `STORAGE_S3_BUCKET` (the
bucket from `s3.tf`, passed in by `ec2.tf`), `MANAGEMENT_HEALTH_MAIL_ENABLED=false`,
`REQUIRE_MODEL=true`, `LOG_FORMAT=json`, `SPRING_PROFILES_ACTIVE=prod`,
`AI_SERVICE_ENV=production`.

Firebase push credentials are not provisioned by the bootstrap: the Flutter app
does not register device tokens yet (docs/SMS_PROVIDER_CONFIGURATION.md, Push),
so `NOTIFICATION_PUSH_ENABLED` should stay `false`.

## Parameters intentionally NOT listed here (already handled elsewhere)

- `DB_USERNAME` / `DB_PASSWORD` — never manually created; sourced at boot
  from the RDS-managed Secrets Manager secret (`aws_db_instance.mysql`'s
  `manage_master_user_password`, see `../aws/terraform/rds.tf`).
