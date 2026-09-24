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
                         │     │                      │
                         │     ▼ 127.0.0.1:8080        │
                         │   docker compose:           │
                         │     backend  (Spring Boot)  │
                         │     ai-service (FastAPI) ◄──┼── internal only, never public
                         │   EBS volume: backend_storage (complaint photos)
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

Images (`ghcr.io/<owner>/backend:<tag>`, `ghcr.io/<owner>/ai-service:<tag>`)
are built and published by Phase 21's `release-image-publish.yml` on a
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
| `docker-compose.prod-override.yml` | Swaps `docker/docker-compose.yml`'s build-from-source services for the published GHCR images; drops the local `mysql` container in favor of RDS. |
| `nginx/jannet.conf` + `nginx/README.md` | Reverse proxy + TLS (certbot) config and setup notes. |
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
   set the three GitHub Actions variables).
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

## Object storage — explicit open item, not built this phase

Complaint photos are still stored via the Phase 6 `LocalStorageService`
(local disk), backed here by the EC2 instance's own EBS root volume
(`ec2_root_volume_gb`, default 30 GB) rather than a fresh S3 bucket.
This was a deliberate scope decision, not an oversight:
- The `StorageService` interface (Phase 6) already exists specifically
  to make a future S3 implementation a swap-in, no `ComplaintService`
  change — but writing that new `S3StorageService` class is backend
  **application source** work, outside this phase's `deployment/` scope
  per `ARCHITECTURE.md` Section 8.
- A single-instance EBS volume is a real, working, if less durable,
  storage backend for a pilot's scale — acceptable for the SRS's
  academic-pilot framing (Section 30), not silently broken.
- Risk this leaves open: photos are lost if the EC2 instance's EBS
  volume is lost (instance termination without volume retention,
  hardware failure). Mitigate today by enabling EBS snapshots (not
  automated by this phase's Terraform — a real gap, see below) until a
  future phase migrates to S3.

## Known gaps (documented honestly, not hidden)

- **No live execution** — nothing in this directory has been run
  against a real AWS account (see `VERIFICATION.md`).
- **S3 migration** for complaint-photo storage — see above.
- **RDS TLS certificate is not chain-verified** — `application-prod.yml`
  connects with `useSSL=true&verifyServerCertificate=false`; the
  connection is encrypted but the server certificate isn't validated
  against AWS's RDS CA bundle. Full pinning needs a truststore packaged
  into the runtime image — left open (see that file's own comment and
  `PROJECT_INTEGRATION.md` Section 6).
- **SRS 28's four application-level alert conditions** (API error rate
  >5%, AI classification P95 latency >15s, analytics job failures,
  SLA-breach rate) are NOT implemented — they require custom metric
  emission from backend/ai-service application code, which is out of
  this phase's `deployment/`-only scope. Only infrastructure-level
  alerts (EC2 status check, RDS CPU/storage) are built.
  See `aws/terraform/dns_and_monitoring.tf`'s header comment.
- **No automated EBS snapshot schedule** for the app host's storage
  volume (complaint photos) — a real risk given the "Object storage"
  section above; a `aws_dlm_lifecycle_policy` resource would close this
  and is a reasonable next addition, not built this phase to keep scope
  matched to the explicit brief.
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
