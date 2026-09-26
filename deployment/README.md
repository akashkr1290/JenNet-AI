# JanNet AI — Phase 22: Production Deployment on AWS

This directory is the Phase 22 deliverable (`ARCHITECTURE.md` Section 8:
`22 | deployment/`). It contains the complete infrastructure-as-code,
bootstrap tooling, and runbooks needed to run JanNet AI on AWS. **No live
AWS deployment was performed** — this sandbox has no AWS account/
credentials (see `VERIFICATION.md` for the full breakdown of what was
and wasn't validated). Everything here is a real, complete, ready-to-run
configuration for an operator with AWS access to execute.

## Architecture

```
                                   Internet
                                       │
                         ┌─────────────┴─────────────┐
                         │        Route53 (opt.)      │
                         │   api.<domain> -> EIP       │
                         └─────────────┬─────────────┘
                                       │  HTTPS (443) / HTTP (80, ACME+redirect only)
                         ┌─────────────▼─────────────┐
                         │   EC2 app host (public subnet)
                         │   ─────────────────────────
                         │   nginx (TLS termination)  │
                         │     │ /api/v1 -> 127.0.0.1:8080
                         │     │ /       -> 127.0.0.1:3000
                         │   docker compose:           │
                         │     backend  (Spring Boot)  │
                         │     flutter-frontend (web)  │
                         │     ai-service (FastAPI) ◄──┼── internal only, never public
                         │   complaint photos -> S3 bucket (s3.tf)
                         └─────────────┬─────────────┘
                                       │  MySQL/TLS (3306), app-host SG only
                         ┌─────────────▼─────────────┐
                         │  RDS MySQL 8.0 (DB subnet, │
                         │  no internet route)        │
                         └────────────────────────────┘

Supporting, not shown above: IAM instance role (SSM + Secrets Manager
read, scoped), SSM Parameter Store (app secrets), Secrets Manager
(RDS-managed master password), CloudWatch alarms + SNS (EC2 status
check, RDS CPU/storage), GitHub OIDC deploy role (CI -> SSM Run Command,
no static AWS keys).
```

Images (`ghcr.io/<owner>/backend:<tag>`, `ghcr.io/<owner>/ai-service:<tag>`,
`ghcr.io/<owner>/frontend:<tag>` - owner/repository lowercased) are built and published by Phase 21's `release-image-publish.yml` on a
`v*.*.*` tag — this phase never rebuilds or republishes them; it only
deploys what Phase 21 already produced and scanned.

## Directory contents

| Path | Purpose |
|---|---|
| `aws/terraform/` | Full IaC — VPC, security groups, RDS, EC2, IAM, GitHub OIDC role, CloudWatch/SNS, optional Route53. See its own `README.md`. |
| `scripts/ec2-user-data.sh.tpl` | Rendered by Terraform into the EC2 instance's first-boot user-data. |
| `scripts/deploy.sh` | Roll a new (already-published) image tag out to the running instance via SSM Run Command. |
| `scripts/rollback.sh` | Roll back to an explicit tag, or auto-rollback to whatever was running before the last `deploy.sh`. |
| `scripts/health-check.sh` | Post-deploy public-endpoint verification. |
| `scripts/wait-for-images.sh` | Used by `deploy-aws.yml`: waits until the three release images exist in GHCR (the publish and deploy workflows start on the same tag). |
| `docker-compose.prod-override.yml` | Swaps `docker/docker-compose.yml`'s build-from-source services for the published GHCR images; drops the local `mysql` container in favor of RDS. |
| `nginx/jannet.conf` + `nginx/jannet-http.conf` + `nginx/README.md` | Reverse proxy (web app at `/`, API at `/api/v1/`) + TLS config; the HTTP-only config used before the certificate exists; setup notes. |
| `ssm/PARAMETERS.md` | Exact list of SSM parameters an operator must create before first boot (names only — never values). |
| `flutter/build_release.sh` | Production Flutter build pointed at the deployed API. |
| `VERIFICATION.md` | What was and wasn't actually validated in this sandbox. |
| `ROLLBACK.md` | Rollback decision tree and procedure. |
| `DEPLOYMENT_CHECKLIST.md` | Pre-flight / execution / post-flight checklist for a real operator. |

## Deploy sequence (first time)

1. Confirm a `v*.*.*` tag has already been pushed and
   `release-image-publish.yml` succeeded (Phase 21) — this phase deploys
   an existing image, it does not build one.
2. `cd deployment/aws/terraform` and follow that directory's own
   `README.md` (`terraform apply`, then populate SSM parameters, then
   set the three GitHub Actions variables). Also set the repository
   variable `PRODUCTION_WEB_API_BASE_URL` (e.g. `https://<domain>/api/v1`)
   BEFORE tagging: the web frontend image compiles it in, and
   `release-image-publish.yml` fails its frontend job without it.
3. Wait for the EC2 instance's first boot to complete —
   `scripts/ec2-user-data.sh.tpl` logs to
   `/var/log/jannet-ai-bootstrap.log` on the instance.
4. Run `scripts/health-check.sh https://<your-domain>` (or trigger
   `.github/workflows/deploy-aws.yml` via `workflow_dispatch`, which
   runs the same check).
5. Follow `DEPLOYMENT_CHECKLIST.md`'s "Post-flight" section before
   declaring the pilot live.

## Deploy sequence (subsequent releases)

Push a new `v*.*.*` tag. `release-image-publish.yml` (Phase 21) builds
and publishes it; `deploy-aws.yml` (this phase) then deploys it to the
existing instance automatically — no Terraform re-apply needed unless
infrastructure itself changed.

## Staging environment (Gap-backlog Patch 39/56/57, Sep 2026 audit)

`variables.tf`'s `environment` variable (default `"pilot"`) already
parameterizes every resource name/tag in this Terraform config — a
staging stack is a separate Terraform state, not new code:

```
cd deployment/aws/terraform
terraform workspace new staging   # or use a separate backend config/state file
terraform apply -var="environment=staging"
```

Once that stack exists, set these repository variables (Settings →
Actions → Variables) so `deploy-aws.yml`'s `deploy-staging` job can
actually reach it:

| Variable | Value |
|---|---|
| `STAGING_EC2_INSTANCE_ID` | the staging stack's EC2 instance ID (Terraform output) |
| `STAGING_API_BASE_URL` | the staging stack's public health-check URL |

Every tagged release then deploys to staging first, runs a health-check
smoke test, and only proceeds to the production `deploy` job if that
passes (or if `STAGING_EC2_INSTANCE_ID` isn't set yet, in which case a
workflow warning is logged and production deploy proceeds unguarded —
documented, not silent). Model changes (Gap-backlog Patch 2/3's own
"AI model changes should first be tested in staging") follow the same
path: push a new model version, deploy, verify against staging traffic
before promoting.

**NOT VERIFIED**: no live AWS account in this sandbox to actually run
`terraform apply -var="environment=staging"` or the resulting workflow
— this is the real next step for someone with AWS access, not a claim
that a staging stack has been created.

## Object storage (audit fix Phase 07, GAP-018)

Production stores complaint photos in the S3 bucket `s3.tf` creates:
`ec2-user-data.sh.tpl` writes `STORAGE_PROVIDER=s3` and `STORAGE_S3_BUCKET`
(passed in by `ec2.tf`), and the instance role already has access
(`s3.tf`'s `s3_media_access` policy). The bucket is private, versioned and
encrypted (SSE AES-256); photos are served through presigned URLs
(`S3StorageService`). The backend refuses to start with `STORAGE_PROVIDER=s3`
and no bucket name. Previously the bucket was created but never used and
photos lived on the instance's EBS volume. **NOT VERIFIED** against a real
bucket (no AWS account in the fix environment).

## Audit fix Phase 07 — production topology (GAP-017, GAP-018)

- The Flutter web app is published as an image and served by nginx at `/`
  (previously `location / { return 404; }` and the production compose file
  would have built Flutter from source on the host).
- `deploy.sh` and the bootstrap used `../docker-compose.prod-override.yml`
  and `../nginx/jannet.conf`, which resolve **outside** the cloned
  repository; both now use `deployment/...` paths from the repository root,
  and pass `--env-file .env` (without it `${AWS_REGION}` / `${LOG_GROUP_PREFIX}`
  were empty for the awslogs driver).
- nginx starts with `nginx/jannet-http.conf` and switches to the HTTPS
  config after `certbot certonly --webroot` succeeds (the old order could not
  start nginx before a certificate existed). A systemd timer renews the
  certificate.
- The bootstrap `.env` contains every production setting (see
  `ssm/PARAMETERS.md`), and the backend refuses to start under `prod` when a
  required value is missing or unsafe.
- Backend, ai-service and frontend are published on `127.0.0.1` only.
- `deploy.sh` checks out the release tag before starting it, so compose and
  nginx files always match the images; `deploy-aws.yml` waits for the images
  to be published before deploying.

## Known gaps (documented honestly, not hidden)

- **No live execution** — nothing in this directory has been run
  against a real AWS account (see `VERIFICATION.md`).
- Items listed here before the Sep 2026 fixes that are now closed: S3
  storage (see above), RDS certificate-chain verification
  (`application-prod.yml`, truststore baked into the backend image),
  application-level CloudWatch alarms and the EBS snapshot policy
  (`aws/terraform/app_monitoring.tf`). Application-level alarms are still
  limited to what the application logs (see that file's header).
- **Flutter release signing** — `flutter/build_release.sh` can produce
  an unsigned/debug-signed release APK for pilot sideload distribution,
  but a real Play Store `appbundle` needs a real upload keystore this
  project has never had (carried forward from Phase 21's own
  "no signed Flutter release build" gap) — still open.
- **Single-instance, single-AZ** — no Auto Scaling Group, no
  Application Load Balancer, `db_multi_az = false` by default. Matches
  the SRS's explicit low-cost-infrastructure pilot constraint (Section
  30) and Section 7's "avoid overengineering" non-goal; see "Scaling
  beyond the pilot" below for the upgrade path if/when warranted.
- **GHCR image visibility** — this config supports both public and
  private GHCR packages (`ssm/PARAMETERS.md`'s `GHCR_PULL_TOKEN`), but
  which one this project's actual repository uses was not something
  this phase could check (no live GitHub repository/package visibility
  setting reachable from this sandbox).

## Scaling beyond the pilot

If a real municipal deployment outgrows this design: replace the single
EC2 host with an ECS Fargate service (or an Auto Scaling Group) behind
an Application Load Balancer + ACM certificate (removes the nginx/
certbot layer entirely), flip `db_multi_az = true`, and move object
storage to S3. None of this is built now — Section 7 of
`ARCHITECTURE.md` explicitly instructs against introducing
infrastructure beyond a demonstrated requirement, and no such
requirement exists yet for a not-yet-launched academic pilot.
