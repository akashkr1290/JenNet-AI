# Phase 22 — Deployment Checklist

For a real operator with AWS credentials executing this phase's
deliverable for the first time.

## Pre-flight

- [ ] `release-image-publish.yml` (Phase 21) has succeeded at least once
      — a `v*.*.*` tag exists with corresponding `backend` and
      `ai-service` images pushed to GHCR.
- [ ] Confirm whether those GHCR packages are public or private. If
      private, prepare a GitHub PAT with `read:packages` scope for
      `ssm/PARAMETERS.md`'s `GHCR_PULL_TOKEN`.
- [ ] Decide on a domain name (or accept IP-only/HTTP-only for an
      initial smoke test — see `nginx/README.md`'s degraded-mode note).
- [ ] Create an EC2 key pair out-of-band: `aws ec2 create-key-pair
      --key-name jannet-ai-pilot --query 'KeyMaterial' --output text >
      jannet-ai-pilot.pem && chmod 400 jannet-ai-pilot.pem`. Store the
      `.pem` somewhere safe outside this repo.
- [ ] Generate real values for every parameter in `ssm/PARAMETERS.md`
      and create them via `aws ssm put-parameter` (SecureString).
- [ ] Copy `aws/terraform/terraform.tfvars.example` to
      `terraform.tfvars` and fill in real values — especially
      `admin_cidr` (never leave it world-open) and `github_repo`.

## Execution

- [ ] `terraform init && terraform plan` — read the plan output fully
      before applying; this is genuinely the first time this exact
      configuration has ever been planned against a real account (see
      `VERIFICATION.md`).
- [ ] `terraform apply`.
- [ ] Tail `/var/log/jannet-ai-bootstrap.log` on the new instance (via
      SSM Session Manager or SSH) until it prints "bootstrap complete."
- [ ] If a domain was configured, confirm the Route53 (or external DNS)
      record resolves to the `app_host_public_ip` output before
      `certbot` runs — a DNS propagation race is a real, documented risk
      (`VERIFICATION.md`).
- [ ] Set the three GitHub Actions repo variables:
      `EC2_INSTANCE_ID`, `AWS_DEPLOY_ROLE_ARN`, `API_BASE_URL`.

## Post-flight

- [ ] `./scripts/health-check.sh https://<domain>` returns `UP`.
- [ ] Manually exercise one real end-to-end flow: submit a test
      complaint via the Flutter app (built with
      `flutter/build_release.sh` pointed at the new `API_BASE_URL`) and
      confirm it reaches `AI_PROCESSING` status and gets classified.
- [ ] Confirm the bootstrap super-admin account
      (`BOOTSTRAP_SUPER_ADMIN_MOBILE`/`_PASSWORD`) can log in, then
      **immediately rotate that password** through the app rather than
      leaving the SSM-provisioned one in place indefinitely.
- [ ] Confirm the SNS alerts subscription email was received and
      confirmed (SNS requires an explicit confirmation click).
- [ ] Push a trivial `v*.*.*+1` tag and confirm `deploy-aws.yml` runs
      end-to-end automatically, to validate the CI deploy path itself
      (not just the manual Terraform path) before relying on it for a
      real release.
- [ ] Record actual results (pass/fail per item above) in
      `PROJECT_INTEGRATION.md` Section 6 — this checklist's real value
      is in a filled-out first run, not as a template that stays
      hypothetical forever.

## Rollback trigger

Any post-flight item failing that isn't quickly fixable → see
`ROLLBACK.md`.
