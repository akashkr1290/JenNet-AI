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

## Parameters intentionally NOT listed here (already handled elsewhere)

- `DB_USERNAME` / `DB_PASSWORD` — never manually created; sourced at boot
  from the RDS-managed Secrets Manager secret (`aws_db_instance.mysql`'s
  `manage_master_user_password`, see `../aws/terraform/rds.tf`).
- `NOTIFICATION_EMAIL_ENABLED` / `NOTIFICATION_SMS_ENABLED` /
  `SMTP_HOST` / `SMTP_PORT` / `SMS_PROVIDER_URL` — non-secret config
  values; set these directly in
  `../scripts/ec2-user-data.sh.tpl`'s `.env` block (or a future
  Terraform variable) rather than SSM, following `.env.example`'s own
  distinction between secret and non-secret settings.
