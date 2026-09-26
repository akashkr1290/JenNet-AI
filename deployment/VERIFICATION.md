# Phase 22 — Verification

Consistent with this project's convention since Phase 1 ("honest
documentation over false completeness" — `PROJECT_INTEGRATION.md`
Section 6, restated in every `PHASE_HANDOFF.md` entry), this file states
plainly what was and wasn't actually executed for Phase 22.

## Persistent environment constraint (unchanged from every prior phase)

This sandbox has:
- No AWS account or credentials.
- No network reach to `registry.terraform.io` (Terraform provider
  downloads), `token.actions.githubusercontent.com`, or any AWS API
  endpoint. Only the domains listed in this workspace's own
  `network_configuration` are reachable (`pypi.org`, `npmjs.org`,
  `github.com`/`codeload.github.com`, `api.anthropic.com`, and a handful
  of package registries) — none of which are the AWS/Terraform surface
  this phase's deliverable targets.
- No Docker daemon (same constraint noted in every Dockerfile since
  Phase 18).
- No Maven Central reach, no Flutter SDK (same constraints noted since
  Phase 3/6 respectively).

This means **zero live execution was possible for this phase's actual
deliverable** — a materially larger verification gap than any prior
phase, which at least had CI-runner or local-tool paths for parts of
their own scope. This is stated here directly rather than glossed over.

## What WAS actually validated in this sandbox

- **Terraform HCL syntax**: every `.tf` file under `aws/terraform/`
  reviewed line-by-line for HCL syntax correctness (block structure,
  reference syntax, `jsonencode`/`templatefile` usage) — not run through
  `terraform validate` (no Terraform binary + provider plugins reachable
  here), but no syntax error is knowingly present.
- **Resource/argument names** cross-checked against the `hashicorp/aws`
  provider's documented schema from training-data knowledge for the 5.x
  major version (e.g. `manage_master_user_password`,
  `master_user_secret[0].secret_arn`, `aws_iam_openid_connect_provider`,
  SSM's `/aws/service/ami-amazon-linux-latest/...` published parameter
  path) — not fetched live (no registry reach), so treat this the same
  way Phase 21 treated its own Action-tag verification: high-confidence,
  not proof.
- **YAML syntax**: `.github/workflows/deploy-aws.yml` parsed with
  `yaml.safe_load` — confirmed syntactically valid (same `on:` ->
  boolean-key PyYAML quirk noted in Phase 21's own validation, not a
  real defect).
- **Shell script syntax**: every `.sh`/`.sh.tpl` file checked with
  `bash -n` (syntax-only parse, no execution) — all pass.
- **Cross-file consistency**: every environment variable name the
  bootstrap script writes into `.env` (`JWT_SECRET`, `DB_PASSWORD`,
  `AI_SERVICE_API_KEY`, etc.) cross-checked character-for-character
  against the real `.env.example` (Phase 18) and
  `backend/src/main/resources/application.yml`/`application-prod.yml`
  — all names match exactly.
- **Docker Compose override syntax**: `docker-compose.prod-override.yml`
  reviewed against Compose's documented merge semantics (`!reset`,
  `profiles:`) — not run through `docker compose config` (no Docker
  daemon here).
- **nginx config**: `nginx/jannet.conf` reviewed against nginx's
  documented directive syntax — not run through `nginx -t` (no nginx
  binary in this sandbox).
- **SRS cross-reference**: every SRS clause this phase claims to satisfy
  (27.2 TLS-in-transit, 28 monitoring/health-checks) re-read directly
  from `JanNet_AI_SRS_BRD_FRS.docx` via `python-docx`, not from a prior
  phase's prose summary.

## Audit fix Phase 07 (Sep 2026) — what was checked in the fix environment

- `nginx -t` (nginx 1.24) on `jannet.conf` (with a self-signed certificate at
  the referenced path) and `jannet-http.conf`: both OK. A routing test with
  stub upstreams on 127.0.0.1:8080 / :3000 confirmed: `/` -> frontend,
  `/api/v1/` -> backend with nginx's own `X-Request-Id` (a client-supplied
  one is replaced), `/actuator/health` -> backend, HTTP -> HTTPS redirect,
  HSTS / nosniff / DENY headers, TLS 1.1 refused. The `http2 on;` directive
  was replaced by `listen ... ssl http2` because nginx 1.24 rejects it.
- `ec2-user-data.sh.tpl`: rendered with a script that applies Terraform
  `templatefile()` interpolation rules (every `${...}` must be a passed
  variable, `$${` is a literal). The ORIGINAL template fails that check
  (`${ACME_EMAIL:-admin@$DOMAIN_NAME}` is not a template variable -
  `terraform apply` would have rejected it). The new template passes;
  `bash -n` of the rendered script passes; the `.env` section was EXECUTED
  with a stubbed `aws` CLI and produced the expected file (unset parameters
  omitted, defaults applied, mode 600).
- The generated `.env` + `docker compose --env-file .env -f
  docker/docker-compose.yml -f deployment/docker-compose.prod-override.yml
  config` in a clean copy: valid; only ai-service, backend and
  flutter-frontend; all published on 127.0.0.1; GHCR images; prod
  profile; awslogs groups `/jannet-ai/pilot/{backend,ai-service,frontend}`.
  It also showed that `depends_on: !reset` (old override) removed the
  dependency instead of replacing it - now `!override`.
- Not run: `terraform validate/plan/apply` (no terraform binary), the
  bootstrap on a real Amazon Linux 2023 instance, certbot against Let's
  Encrypt, GHCR publishing, the GitHub workflows.

## What was NOT verified (real first-run risk)

- Whether `terraform apply` actually succeeds against a real AWS
  account with these exact resource definitions.
- Whether `ec2-user-data.sh.tpl` actually completes successfully on a
  real Amazon Linux 2023 instance — in particular, the `git clone
  --branch "$APP_VERSION_TAG"` step assumes the deploying repository is
  public and clonable by tag name over HTTPS with no credential; a
  private source repository (separate from GHCR package visibility)
  would need an additional token this script does not currently handle.
  **Flagged here explicitly as the single most likely first-boot
  failure point**, not discovered by any execution.
- Whether GHCR packages published by this project's real
  `release-image-publish.yml` runs are public or private — if private,
  `GHCR_PULL_TOKEN` (`ssm/PARAMETERS.md`) must be set or the `docker
  compose pull` step in both the bootstrap script and `deploy.sh` will
  fail with an auth error.
- Whether the GitHub OIDC trust condition in `github_oidc.tf`'s
  `StringLike` block correctly matches this specific repository's real
  tag-push and `workflow_dispatch` `sub` claim formats — GitHub's OIDC
  `sub` claim format is documented and stable, but was not tested
  against a live token from this actual repository.
- Whether `certbot certonly --webroot` (audit fix Phase 07; previously
  `certbot --nginx`) succeeds unattended inside a `dnf`-based
  user-data script the very first time it runs (network timing, DNS
  propagation delay between `terraform apply` creating the Route53
  record and the EC2 instance reaching the ACME HTTP-01 challenge).
- Whether `t3.small` is actually sufficient memory/CPU for backend +
  ai-service + nginx running concurrently under any real load — see
  `aws/terraform/README.md`'s sizing note; this is a reasoned estimate,
  not a benchmarked number.
- Whether the RDS parameter group's `require_secure_transport=ON`
  combined with `application-prod.yml`'s new
  `useSSL=true&requireSSL=true` JDBC URL actually establishes a
  connection Hibernate/Flyway can use to run the inherited V1–V18+
  migrations — this remains layered on top of this project's own
  longest-standing carried-forward gap: **the V1–V18+ Flyway migrations
  and `mvn -pl backend clean verify` have never been run against any
  real MySQL instance in this project's history** (noted in every prior
  phase's own progress doc). A real RDS instance would be the first
  time this ever happens for real — treat a migration failure there as
  a genuine, valuable first-run finding per this project's established
  convention, not a deployment-config defect by default.

## If this is the first real run and something fails

Per this project's own established triage convention (`PHASE_HANDOFF.md`
Phase 21 entry, "Next phase" section): a failure the very first time any
of this actually executes is very likely a genuine defect surfacing for
the first time, not something to silently work around. Fix it, document
the real finding in `PROJECT_INTEGRATION.md` Section 6, and re-verify —
do not assume the deployment tooling itself is wrong before checking
whether the underlying inherited backend/ai-service/migration code is
what actually failed.
