# Audit fix Phase 07 — production hardening

Gaps in this phase, per the audit's recommended sequence item 7 ("Production
topology and hardening"): GAP-017, GAP-018, GAP-041, GAP-042, GAP-048,
GAP-049, GAP-056, GAP-057.

- **Already fixed in earlier fix phases, and re-checked here without changes:**
  - GAP-042: rollback condition in `deploy-aws.yml` — Phase 02.
  - GAP-057: Newman schedule guard and Android release identity — Phase 02.
  - GAP-048: dev seed gated with random passwords — Phase 01.
  - GAP-049: client-IP resolution and resend-OTP enumeration — Phase 01.
  - GAP-056: configuration hygiene and attempts clamp — Phase 05.
- **Implemented in this phase:** GAP-017, GAP-018 and GAP-041. The deployment
  path bug the user identified is part of GAP-017 and GAP-018.

New migration: `V30__add_user_erased_at.sql`.

## GAP-017 — the web app in production (SRS 11.1, 11.2)

- **Publishing.** `release-image-publish.yml` publishes
  `ghcr.io/<owner/repo lowercased>/frontend:<tag>`.
  - It is built from `docker/Dockerfile.flutter-web` with
    `API_BASE_URL=${{ vars.PRODUCTION_WEB_API_BASE_URL }}`.
  - The job fails loudly when that variable is missing or does not look like
    `https://<domain>/api/v1`.
  - It is a separate job, so the backend and ai-service images still publish.
- **Lowercase image names.** Image names are now lowercased. GHCR rejects
  capitals, and this repository's name contains capitals.
- **Compose override.** `deployment/docker-compose.prod-override.yml` runs the
  published frontend image and never builds on the host. Backend, ai-service
  and frontend are published on `127.0.0.1` only. Its `depends_on` uses
  `!override`; the previous `!reset` silently removed the dependency.
- **nginx routing.** `deployment/nginx/jannet.conf` proxies `/` to the
  frontend container and `/api/v1/` to the backend, which is the same origin.
  It forwards `X-Request-Id $request_id`.
- **HTTP-first bootstrap.** The new `jannet-http.conf` is the plain-HTTP
  config used before a certificate exists, and in the documented no-domain
  degraded mode.
- **Deployment path fixes:**
  - `deploy.sh` and `ec2-user-data.sh.tpl` pointed to
    `../docker-compose.prod-override.yml` and `../nginx/jannet.conf`, which
    resolve outside the clone. Both now use `deployment/...` from the
    repository root.
  - Both now pass `--env-file .env`.
  - `deploy.sh` checks out the release tag first.
  - Frontend deploy and health check are added.
- **Other bootstrap defects found and fixed:**
  - `${ACME_EMAIL:-...}` is invalid inside a Terraform template, so
    `terraform apply` would have rejected it. It is now escaped as `$${...}`.
  - nginx could not start before the certificate existed. Certificates are
    now issued with the webroot method, and a renewal timer is added.
- **Log groups.** The frontend log group was added. The EC2 instance now
  depends on its log groups, because awslogs runs with `create-group=false`.
- **Image wait.** `deploy-aws.yml` waits for all three release images
  (`wait-for-images.sh`). The publish and deploy workflows start on the same
  tag, so the deploy used to pull images that were still building.

## GAP-018 — complete production configuration

- **Generated `.env`.** The bootstrap `.env` now sets:
  - the notification switches;
  - SMTP host, port and sender;
  - all SMS adapter settings;
  - CORS origin (`https://<domain>`);
  - `STORAGE_PROVIDER=s3` with the Terraform bucket;
  - the image-signing secret (only relevant for local storage);
  - `GEMINI_MODEL_NAME`;
  - `MANAGEMENT_HEALTH_MAIL_ENABLED=false`;
  - `REQUIRE_MODEL=true`;
  - `LOG_FORMAT=json`;
  - the `GEO_*` boundary;
  - profanity and report settings.
- **Where values come from.** Secrets come from SSM SecureString parameters
  and plain settings from String parameters. An unset parameter writes
  nothing, so the application default stays in force. See
  `deployment/ssm/PARAMETERS.md`.
- **Startup check.** `ProductionConfigurationValidator` (prod profile only)
  refuses to start when:
  - S3 is selected without a bucket;
  - local storage uses the public signing secret;
  - CORS lists a localhost or `*` origin, or is empty;
  - the ai-service key is the public placeholder;
  - e-mail, SMS or push is enabled but incomplete.

  It warns, without stopping, when no OTP channel is enabled or the `GEO_*`
  box is still the all-India placeholder. Log messages name the variable,
  never its value.

## GAP-041 — observability and compliance (SRS 24, 26, 27)

| Requirement | Done | Where |
|---|---|---|
| JSON logs with timestamp, service, level, correlation id (SRS 27) | Yes | Backend: `logback-spring.xml` + `JsonLogLayout` under the `prod` profile, with no new dependency. ai-service: `JsonFormatter`; `LOG_FORMAT`, defaulting to JSON outside local. |
| Correlation id (SRS 26 "logged with a correlation ID") | Yes | nginx `$request_id` → backend `RequestIdFilter` (MDC plus `X-Request-Id` response header). It is copied to the async executors and forwarded to ai-service, whose middleware and log filter use it. Malformed incoming ids are replaced (no log injection). |
| Transient DB retry ×3 with exponential backoff (SRS 26) | Yes, for connection loss | `RetryingJpaTransactionManager` retries only the start of a transaction (getting the connection), at 200/400/800 ms. Hikari connection timeout is 5 s. Failures inside a running transaction are **not** replayed, because that could apply a change twice. |
| Log retention ≥ 90 days (SRS 27) | Yes | CloudWatch log groups 30 → 90 days. Audit-relevant records stay in the database. |
| PII masked in logs (SRS 27) | Already the case | All logger calls that mention a phone or e-mail use `PiiMask` (re-checked). |
| Access request (SRS 24) | Yes | `GET /api/v1/users/me/data-export`, plus "Copy my personal data" in Settings. |
| Erasure request (SRS 24) | Yes | Citizens: `POST /api/v1/users/me/erase` with the password, and "Delete my account" in Settings. On behalf of a person: `POST /api/v1/admin/users/{id}/erase` (SUPER_ADMIN only). See "Erasure" below. |
| AES-256 for sensitive stored fields "where applicable" (SRS 27.2) | **Storage layer only** | RDS `storage_encrypted = true` (AES-256, KMS) and the S3 bucket's SSE AES-256 encrypt every stored field at rest. Application-level field encryption was **not** added; see below. |

### Erasure

What happens:
- The account row stays, because complaints, status history and audit logs
  reference it.
- Name, mobile, e-mail and password are replaced. The mobile becomes
  `ERASED<id>`, which is never a valid number. Verification timestamps are
  cleared.
- The status becomes SUSPENDED and `erased_at` is set. Reactivation is refused
  with 409.
- Sessions are revoked. Device tokens, OTP rows and personal settings are
  deleted.
- The audit entry holds identifiers only.

**Product-owner / legal decision:** complaint descriptions, photos,
locations, rating comments and appeal texts are kept. They are civic records
about public places. Whether they must be redacted on erasure, and the
retention policy, need a decision under the applicable Indian data-protection
rules.

### Why no application-level field encryption

`users.mobile_number` and `users.email` are login keys with unique indexes,
and `mobile_number` is `VARCHAR(15)`.

Encrypting them would need all of the following:
- deterministic encryption or a blind index for lookups;
- wider columns;
- a data migration that encrypts existing rows with a key that SQL cannot see;
- key management (AWS KMS, rotation).

That changes login, registration, admin search and the dev seed, and none of
it could be executed here (no Maven). Storage-level AES-256 is in place.
Field-level encryption is recorded as a **security/product-owner decision
with a design prerequisite (key management)**, not implemented.

## Verification summary

See `/home/claude/fixes/verification/LEDGER.md` (Phase 07).
