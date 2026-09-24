# Phase 22 — Rollback

## When to roll back vs. fix forward

| Situation | Action |
|---|---|
| New deploy fails its own health check (`deploy.sh`'s post-deploy `curl` step) | `deploy.sh` itself exits non-zero and the previous containers are still what's running (Compose `up -d` on a failed pull never tears down the currently-running containers) — usually **no action needed**, but confirm with `scripts/health-check.sh` against the public URL. |
| New deploy passes health checks but a real functional regression is found afterward (e.g. citizens report a broken flow) | Roll back: `./scripts/rollback.sh <instance-id> --auto` |
| A specific older tag is known-good and you want to skip the "auto" (last-deployed) tag entirely | `./scripts/rollback.sh <instance-id> <ghcr-owner> <tag>` |
| Database migration in the new version is suspected to have partially applied | **Do not roll the application back alone** — Flyway migrations are one-way by design (`baseline-on-migrate: false`, `validate-on-migrate: true` in `application.yml`). Rolling the app back to a version expecting an older schema against an already-migrated database can break in new ways. Restore from the most recent RDS automated backup (`db_backup_retention_days`, default 7) instead: AWS Console/CLI RDS "Restore to point in time," pointed at a timestamp before the bad migration ran, then repoint `DB_HOST` at the restored instance. |
| EC2 instance itself is unhealthy (CloudWatch `ec2-status-check-failed` alarm fired) | This isn't an application rollback — see "Instance-level recovery" below. |

## Application rollback procedure

```bash
# Automatic: roll back to whatever was running immediately before the
# last deploy.sh execution (recorded on the instance itself).
./deployment/scripts/rollback.sh <instance-id> --auto

# Explicit: roll back to a specific known-good tag.
./deployment/scripts/rollback.sh <instance-id> <ghcr-owner> v1.0.0

# Or via GitHub Actions: run deploy-aws.yml manually
# (workflow_dispatch) with rollback=true.
```

Both paths call `deploy.sh` under the hood (`ROLLBACK.md`'s own
guiding principle: a rollback is a deploy to an older tag, not a
different mechanism) — the same health checks that gate a forward
deploy also gate a rollback.

## Instance-level recovery

If the EC2 instance itself is unresponsive (not just the application
containers):
1. Check the CloudWatch alarm / SNS notification for which check
   failed (`aws/terraform/dns_and_monitoring.tf`).
2. AWS Console -> EC2 -> select the instance -> "Instance state" ->
   check system/instance status check details.
3. A `t3` instance that's simply out of burst CPU credits will recover
   on its own; a genuinely crashed instance should be stopped and
   started (not rebooted — a stop/start moves it to new underlying
   hardware, a reboot doesn't) via the console or `aws ec2
   stop-instances`/`start-instances`.
4. **user-data does NOT re-run** on stop/start or reboot (only on a
   brand-new instance launch) — the running Docker containers and their
   `.env` file persist on the root EBS volume across a stop/start, so
   no re-bootstrap is needed for a simple instance-health recovery.
5. If the root volume itself is unrecoverable: this is also when the
   "no automated EBS snapshot schedule" gap noted in `README.md`'s
   "Known gaps" section becomes a real, painful problem — recovery in
   that case means a fresh `terraform apply` (new instance, user-data
   re-runs from scratch) plus manual restoration of complaint photos
   from whatever backup exists, which may be none. This risk is exactly
   why that gap is flagged as a real, non-cosmetic open item rather than
   a nice-to-have.

## Database rollback / point-in-time restore

```bash
aws rds restore-db-instance-to-point-in-time \
  --source-db-instance-identifier jannet-ai-pilot \
  --target-db-instance-identifier jannet-ai-pilot-restored \
  --restore-time 2026-01-15T10:00:00Z
```

This creates a **new** RDS instance (AWS never restores in place) — you
must then update `DB_HOST` (SSM/`.env`) to point at the restored
instance's new endpoint and redeploy, or use Terraform's `import` to
bring the restored instance under management and re-point
`aws_db_instance.mysql`'s configuration. Not automated by any script in
this phase — a deliberately manual, high-stakes operation.

## After any rollback

1. Run `scripts/health-check.sh` against the public URL.
2. Confirm which version is now live: `curl -s
   https://<domain>/actuator/health` doesn't itself report a version —
   check `deploy.sh`'s own output/GitHub Actions run log for the tag
   that was actually rolled back to.
3. Document the incident and root cause in
   `PROJECT_INTEGRATION.md` Section 6 if this surfaces a genuine defect
   (per this project's own "honest documentation" convention), not just
   silently move on.
