# Disaster Recovery — Restore Procedure

Gap-backlog Patch 55 (Sep 2026 audit): "the important part is testing
restoration, not just creating backups." This is a real, step-by-step
restore runbook against this project's actual backup mechanisms — it has
**not** been executed, because no live AWS account is reachable from this
sandbox (the same constraint every AWS-integration file in this project
documents). This is the runbook someone with real AWS access should
actually execute and time, not a claim that a restore has been tested.

## What actually creates backups today

| Data | Mechanism | Where configured |
|---|---|---|
| MySQL (RDS) | Automated RDS snapshots + final snapshot on deletion | `deployment/aws/terraform/rds.tf` (`backup_retention_period`) |
| Complaint media (S3) | S3 versioning + lifecycle transition | `deployment/aws/terraform/s3.tf` (Gap-backlog Patch 5/7/21) |
| Complaint media (local-disk mode, pre-S3) | None — this is exactly why Patch 5/7 moved media to S3 | — |

## Restore procedure — RDS (MySQL)

1. **Identify the snapshot.** `aws rds describe-db-snapshots --db-instance-identifier <id>` — pick the automated snapshot closest to (but before) the incident time, or the final snapshot if restoring after a deliberate deletion.
2. **Restore to a NEW instance** (never restore in-place over the live one): `aws rds restore-db-instance-from-db-snapshot --db-instance-identifier <id>-restored --db-snapshot-identifier <snapshot-id>`.
3. **Wait for `available` status**, then verify: connect with the app's own credentials, run `SELECT COUNT(*) FROM complaints;` and a handful of spot-check queries against known reference data (e.g. `SELECT * FROM wards LIMIT 5;`) to confirm the restore isn't silently truncated or corrupted.
4. **Run Flyway's `info` command** against the restored instance (`mvn flyway:info` or the equivalent CLI) to confirm every migration `V1`–`V20` (as of this audit) shows `Success` — a restore from a snapshot taken mid-migration would show a partial history, which is itself useful signal.
5. **Cut over**: update `DB_HOST` (backend's environment / Terraform `rds.tf` output) to point at the restored instance, redeploy the backend, confirm `/actuator/health` reports `UP`.
6. **Record the actual timings** (snapshot restore duration, verification time, cutover time) — this is the real RTO (Recovery Time Objective) measurement Patch 55 is actually asking for, and it can only come from really doing this once.

## Restore procedure — S3 (complaint media)

1. **Accidental delete/overwrite of one object**: since versioning is enabled (`s3.tf`), the previous version is still in the bucket. `aws s3api list-object-versions --bucket <bucket> --prefix <key>` to find it, then `aws s3api copy-object` (or restore via the console) to make that version current again.
2. **Bucket-wide event**: restore from the most recent version of every object as of the incident time using `aws s3api list-object-versions` + a scripted loop, or AWS Backup if that's layered on top of S3 versioning in a given deployment.
3. **Verify**: spot-check a handful of `ComplaintImage.storageKey` values (from a restored RDS snapshot or the live database) actually resolve via `S3StorageService.presignedUrl` / a direct `GetObject` call.

## What this does NOT cover yet

- **EC2/application server loss**: covered by re-running the Terraform `apply` + CI/CD deploy (Gap-backlog Patch 24/56/57), not a backup/restore concern — the backend is stateless (Gap-backlog Patch 5/7 removed its last piece of local state, complaint media).
- **A real timed drill**: this runbook has never been executed end-to-end against a real environment. Scheduling and running one is the actual remaining work this patch calls for.
