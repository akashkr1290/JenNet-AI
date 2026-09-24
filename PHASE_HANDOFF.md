# Phase Handoff

## Phase 23 — Final Integration Testing — COMPLETE — **THIS IS THE FINAL PHASE. THERE IS NO PHASE 24.**

**Component built:** `all — final integration testing` (per
ARCHITECTURE.md Section 8's Phase -> Component map: `23 | all — final
integration testing`). Verification and genuine-defect-fixing only — no
new features. See `PROJECT_PROGRESS.md`'s Phase 23 body for the full
scope-determination record and per-item breakdown.

**The 21-point verification checklist supplied with this phase's
instructions, mapped to real, already-built work (not invented for this
phase):**

| # | Area | Status |
|---|---|---|
| 1 | Authentication and RBAC | VERIFIED (static) — `SecurityConfig.java`'s full `authorizeHttpRequests` chain reviewed line-by-line; every path group explicit or fails closed via `anyRequest().authenticated()`. Live JWT issuance/validation NOT VERIFIED (no live server — Maven Central unreachable). |
| 2 | Citizen registration/login/MFA | VERIFIED (static) — `AuthController`'s 10 endpoints match Postman 1:1; Flutter `auth_api.dart`/screens (Phase 17) confirmed present. Not live-exercised. |
| 3 | Complaint submission | VERIFIED (static) — `ComplaintController`'s 11 endpoints match Postman 1:1; state machine reviewed (ARCHITECTURE.md Section 4). Not live-exercised. |
| 4 | GPS/location handling | VERIFIED (static) — `V5__create_locations.sql` applied for real against MySQL 8 this phase; `LocationService`/entity mapping unchanged since Phase 6. |
| 5 | AI image verification pipeline | **VERIFIED — REAL EXECUTION**: ai-service pytest suite (74/74) actually run this phase, including `test_pipeline_routing.py`/`test_yolo_service.py`/`test_preprocessing.py`. |
| 6 | Complaint verification/classification | VERIFIED (static + partial real) — classify/duplicate-check routing logic covered by the real pytest run above; backend orchestration (`AiClassificationService`) reviewed, not live-exercised. |
| 7 | Department assignment | VERIFIED (static) — `DepartmentAssignmentService`/`AdminRoutingRuleController` endpoints match Postman; Phase 12's hard department-scope restriction reviewed in `SecurityConfig`/service source. |
| 8 | Officer queue and workflow | VERIFIED (static) — `ComplaintController`'s staff-only endpoints (`/verify`, `/status`, `/assign`) present and role-gated per Phase 11/12/13 decisions. |
| 9 | Department Head workflow | VERIFIED (static) — `DepartmentController` performance/export endpoints + Flutter `department_api.dart`/screens present, matching Phase 13/14 scope. |
| 10 | Admin/Super Admin workflow | VERIFIED (static) — all 4 admin controllers (Users/Settings/RoutingRule/AuditLog), 13 endpoints total, match Postman 1:1; Flutter `admin_api.dart` present. |
| 11 | Notifications | VERIFIED (static) — `NotificationController`'s 3 endpoints match Postman; PUSH-channel gap re-confirmed still open (Phase 15, unchanged — real gap, not re-litigated this phase). |
| 12 | Personal settings | VERIFIED (static) — `UserController`'s `/me/settings` endpoints present; Flutter `settings_api.dart`/screen present (Phase 15). |
| 13 | Dashboards and analytics | VERIFIED (static) — `GovernmentDashboardController`'s 4 endpoints match Postman; Officer/Citizen dashboard gaps re-confirmed still open (Phase 16, unchanged). |
| 14 | Postman/API coverage | **VERIFIED — REAL CHECK**: 56 backend+AI endpoints extracted from live controller/route source, compared 1:1 against the Postman collection's 56 requests — exact match. Both Postman JSON files parse clean. Not run against a live server with Newman (no live server — Maven Central unreachable). |
| 15 | Docker/containerization | VERIFIED (static) — `docker-compose.yml`, both Dockerfiles, and the Phase 22 `docker-compose.prod-override.yml` parse/review clean (the override's `!reset` tag confirmed as legitimate Compose-spec syntax, not a defect, via a Compose-aware YAML loader this phase built). No Docker daemon stood up — deliberately deprioritized in favor of the real MySQL execution below (see PROJECT_PROGRESS.md's "DECISIONS THIS PHASE"). |
| 16 | CI/CD | VERIFIED (static) — all 6 `.github/workflows/*.yml` + `dependabot.yml` parse clean via `yaml.safe_load`. No live GitHub Actions runner. |
| 17 | Production/AWS configuration | VERIFIED (static) — all 10 Terraform `.tf` files brace-balance clean; `terraform validate` NOT VERIFIED (no HashiCorp registry reach). |
| 18 | Database/migrations | **VERIFIED — REAL EXECUTION, GENUINE DEFECT FOUND AND FIXED**: all 18 Flyway migrations run for real against a real, freshly-installed MySQL 8.0.46 server — see "Genuine finding this phase" below. This is this project's single longest-standing gap (flagged unchanged since Phase 3), closed this phase. |
| 19 | Security | VERIFIED (static) — Section 6 (Security Baseline) of ARCHITECTURE.md cross-checked against `SecurityConfig.java`, password hashing (BCrypt), and the Phase 22 TLS/Secrets-Manager/OIDC additions; nothing live-penetration-tested (no live server). |
| 20 | Critical end-to-end workflows | VERIFIED (static) — the full Draft→...→Closed state machine (ARCHITECTURE.md Section 4) and its Duplicate/Rejected/Reopened side states reviewed against `ComplaintStateMachine`/`ComplaintService` source; not live-exercised end-to-end (no live server). |
| 21 | Error handling and edge cases | VERIFIED (static) — SRS Section 26's error envelope reviewed against `RestAuthenticationEntryPoint`/`RestAccessDeniedHandler`/global exception handling; the one genuine edge case this phase found by *actually running* something (the V6 CHECK-constraint/self-referencing-FK conflict) is documented in full above. |

**Genuine finding this phase:** on the very first real execution of this
project's Flyway migrations against a real MySQL server (MySQL 8.0.46,
installed this phase — a first in this project's history), migration
`V6__create_complaints.sql` failed outright with MySQL error 3823:
InnoDB will not allow a column that carries a foreign key's referential
action (here, `fk_complaints_parent_complaint`'s self-referencing
`ON DELETE SET NULL`) to also appear in a CHECK constraint
(`chk_complaints_not_self_parent`). This is a real MySQL/InnoDB platform
restriction that 22 phases of static-only review (brace/paren balance,
manual SQL reading) could never have caught — it only manifests against
a real server, exactly the pattern this project's own `ARCHITECTURE.md`
"Key learnings" section already predicted for exactly this reason.
**Fixed** by removing the CHECK constraint (confirmed safe: the identical
rule is already enforced in `ComplaintService`'s DUPLICATE-decision
branch via an explicit `InvalidStateTransitionException` check before any
row is written, so the CHECK was pure defense-in-depth, not the sole
guard) and documenting the removal directly in the migration file's own
comments. Re-ran all 18 migrations clean after the fix: zero errors, all
15 expected tables created. `database/validation/validate_migrations.py`
re-run clean against the fixed set.

**Second, minor finding this phase:** `deployment/{aws` — a stray, empty,
malformed directory left over from Phase 22's own packaging (almost
certainly an unexpanded shell brace-expansion command, e.g. `mkdir -p
deployment/{aws,terraform,scripts,nginx,ssm}` run under `sh` rather than
`bash`, or with the brace pattern accidentally quoted). Confirmed empty
(zero files, via recursive `find`) before deletion — nothing lost.

**Validation performed (real execution, a first for several of these):**
- MySQL 8.0.46 installed for the first time in this project's history;
  all 18 Flyway migrations run for real, in order, against it (see
  above).
- ai-service pytest suite actually run: **74/74 passing**, confirming
  Phase 20's original real-execution result still holds unchanged at the
  Phase 22 baseline.
- `python3 -m py_compile` on every ai-service `.py` file: zero errors.
- `bash -n` on every shell script in the project (including the
  Terraform-templated EC2 bootstrap script, with its `${...}`
  interpolations mechanically stripped first): zero errors.
- `yaml.safe_load` on all 6 GitHub Actions workflows, `dependabot.yml`,
  and `docker/docker-compose.yml`: all clean. A Compose-spec-aware loader
  confirmed `deployment/docker-compose.prod-override.yml`'s `!reset` tag
  usage is structurally correct (legitimate Compose merge-spec syntax,
  not a YAML defect).
- `json.load` on both Postman JSON files: clean.
- Brace/paren balance checks: 195 backend Java files, 55 Flutter Dart
  files, 10 Terraform `.tf` files — zero imbalances across all of them.
- Full endpoint audit: 51 backend + 5 ai-service = 56 real endpoints
  extracted from live controller/route source, compared 1:1 against the
  Postman collection's 56 requests — exact match.
- `mvn compile` actually attempted (Maven itself newly installable via
  `apt-get` this phase) — failed exactly as expected on the unresolvable
  `spring-boot-starter-parent` parent POM, confirming Maven Central
  remains unreachable rather than assuming it from a prior phase's note.
- **NOT VERIFIED** (see `PROJECT_PROGRESS.md`'s Phase 23 body for the
  complete, itemized list): full backend `mvn clean verify`/compile
  (Maven Central unreachable), Flutter `pub get`/`analyze`/`test`/`build`
  (no Flutter SDK reachable), `terraform validate`/`plan`/`apply` (no
  HashiCorp registry reachable), a real Docker daemon build/compose-up
  (not attempted — deprioritized in favor of the real MySQL execution
  above), any live AWS/GitHub-Actions/Newman execution.

**Scope boundaries respected:** the only source file modified anywhere
in the project is `database/migrations/V6__create_complaints.sql` (the
genuine MySQL 8 defect fix above) — confirmed by a full `diff -rq`
against the untouched `jannet-ai-phase22.zip` extraction, which reports
exactly two differences project-wide: that one file, and the deletion of
the stray `deployment/{aws` directory. Zero new features added. Zero
backend `main/`, ai-service `app/`, flutter `lib/`, docker, or
`.github/workflows/*` application source touched. `postman/` untouched.

**Explicit, honestly-documented gaps carried forward (not this phase's
job to close, per this phase's own "do not invent missing features"
instruction — listed here for completeness, not hidden):**
- No S3 object-storage backend (Phase 22, still open).
- No application-level CloudWatch alerts, only infrastructure-level
  (Phase 22, still open).
- No real push notification delivery — `push_enabled` preference exists,
  nothing is ever dispatched to it (Phase 15, still open).
- Officer Dashboard (SRS 24.2) and Citizen Dashboard (SRS 24.1) not built
  (Phase 16, still open).
- Full Reports Module (PDF export, scheduled email) — CSV-only export
  exists instead (Phase 16, still open).
- Ward dropdown at registration, budget-approval visibility in
  `ComplaintResponse`, Community Heatmap/Rate Resolution/Appeal Rejection
  screens (Phase 17, still open — no backend endpoint exists for the
  latter three).
- RDS TLS encrypted but not certificate-chain-verified (Phase 22, still
  open).
- No signed Flutter release build (Phase 21/22, still open).
- None of these are regressions introduced or discovered this phase —
  each was already known, tracked, and explicitly documented as an open
  item in a prior phase's living-doc entries, and each remains outside
  this final phase's own verification-and-genuine-defect-fix scope.

**Diff audit against Phase 22:** confirmed the smallest, most precise
diff of any phase in this project's history — `diff -rq` against the
untouched Phase 22 baseline extraction reports exactly two differences:
`database/migrations/V6__create_complaints.sql` (the one-constraint fix
above) and the deletion of the empty, stray `deployment/{aws` directory.
Zero files added. Zero other files modified. Zero real project content
deleted.

**jannet-ai-phase23.zip:** packaged and integrity-checked (`unzip -t` +
round-trip extraction diff against the source tree) — see the final
completion summary for the exact result.

---

## Phase 22 — Production Deployment on AWS — COMPLETE

**Component built:** `deployment/` (per ARCHITECTURE.md Section 8's
Phase -> Component map: `22 | deployment/`), plus one new additive
workflow (`.github/workflows/deploy-aws.yml`) and one small, documented
production-config addition (`backend/src/main/resources/application-prod.yml`).

**Deliverables:**
- `deployment/aws/terraform/` — complete IaC: VPC (no NAT Gateway),
  security groups, RDS MySQL (AWS-managed master password, TLS-required
  parameter group), EC2 app host (IAM instance role, no static
  credentials), CloudWatch alarms + SNS, optional Route53 record, and a
  GitHub OIDC deploy role. See `PROJECT_PROGRESS.md`'s COMPLETED section
  for the full per-file breakdown.
- `deployment/scripts/ec2-user-data.sh.tpl` — first-boot bootstrap:
  Docker/Compose/AWS-CLI install, SSM/Secrets-Manager secret fetch,
  `.env` generation (variable names cross-checked against
  `.env.example`), GHCR image pull, `docker compose up`, nginx+certbot.
- `deployment/docker-compose.prod-override.yml`, `deployment/nginx/
  jannet.conf` + `README.md`, `deployment/ssm/PARAMETERS.md`.
- `deployment/scripts/deploy.sh` / `rollback.sh` / `health-check.sh` —
  SSM Run Command based; no SSH key ever required by CI.
- `.github/workflows/deploy-aws.yml` — reacts to the same `v*.*.*` tag
  push `release-image-publish.yml` (Phase 21) reacts to, deploys that
  already-published image via OIDC + SSM, supports manual rollback via
  `workflow_dispatch`.
- `deployment/flutter/build_release.sh` — production Flutter build using
  the existing `--dart-define=API_BASE_URL` mechanism (Phase 6/17); no
  Flutter source changed.
- `backend/src/main/resources/application-prod.yml` — one additive
  prod-profile-only JDBC URL override requiring TLS to MySQL, needed
  because the new RDS parameter group enforces
  `require_secure_transport = ON`. Base `application.yml` (dev/test/local)
  completely unchanged.
- `deployment/README.md`, `VERIFICATION.md`, `ROLLBACK.md`,
  `DEPLOYMENT_CHECKLIST.md` — master runbook, honest verification
  breakdown, rollback decision tree, and operator checklist.
- `.gitignore` — one additive block for Terraform state/`.tfvars`.
- `PROJECT_PROGRESS.md`, `PROJECT_INTEGRATION.md`, `ARCHITECTURE.md` —
  all updated with Phase 22's scope, decisions, and honestly-documented
  limits.

**Validation performed (this workspace has no AWS account/credentials,
no Terraform binary, no network reach to `registry.terraform.io` or any
AWS API endpoint, no Docker daemon, and no live GitHub Actions runner —
the largest verification gap of any phase in this project's history,
stated directly rather than minimized):**
- Every `.tf` file manually reviewed for HCL syntax correctness; every
  `hashicorp/aws` resource/argument name cross-checked against that
  provider's documented 5.x schema from training-data knowledge (not
  fetched live — no registry reach).
- `.github/workflows/deploy-aws.yml` parsed successfully with
  `yaml.safe_load` (same `on:` -> boolean-key PyYAML quirk noted in
  Phase 21, not a real defect).
- Every shell script (`deploy.sh`, `rollback.sh`, `health-check.sh`,
  `ec2-user-data.sh.tpl`, `build_release.sh`) checked with `bash -n` —
  all pass with zero syntax errors.
- Every environment variable name the bootstrap script writes into
  `.env` cross-checked character-for-character against the real
  `.env.example` and `application.yml`/`application-prod.yml` — all
  match exactly.
- `JanNet_AI_SRS_BRD_FRS.docx` re-read directly via `python-docx` for
  every deployment/infrastructure-relevant clause (Section 30's
  low-cost-infrastructure constraint, Section 27.2's TLS requirement,
  Section 28's monitoring requirements) rather than relying on a prior
  phase's summary.
- **NOT VERIFIED** (see `deployment/VERIFICATION.md` for the complete,
  itemized list): whether `terraform apply` succeeds against a real
  account; whether the EC2 bootstrap script's `git clone --branch
  "$APP_VERSION_TAG"` step works against this project's actual real
  repository (flagged as the single most likely first-boot failure
  point — a private source repo would need a credential this script
  doesn't currently pass); whether GHCR packages are public or private
  in practice; whether `certbot --nginx` succeeds unattended on first
  boot; whether `t3.small` is actually sufficient sizing under real
  load; and — layered on top of this project's own longest-standing gap
  — whether the inherited V1-V18+ Flyway migrations actually apply
  cleanly against a real RDS MySQL instance for the first time ever.

**Genuine finding this phase:** none from live execution (none was
possible). One real, substantive design finding from static review: the
new RDS parameter group's `require_secure_transport = ON` would have
broken database connectivity outright against the base `application.yml`
JDBC URL's `useSSL=false` — caught before packaging, not left for a live
`terraform apply` to discover, and fixed with the documented
`application-prod.yml` override described above.

**Scope boundaries respected:** zero backend `main/`/`test/` Java source,
ai-service `app/`/`tests/`, `database/migrations/`, flutter `lib/`/
`test/`, `docker/` (Dockerfiles/compose file themselves), or
`.github/workflows/*` (Phase 21's own five workflows + dependabot.yml)
modified — one new, additive `.github/workflows/deploy-aws.yml` file
only. `postman/` untouched. The single production-source exception
(`application-prod.yml`) is documented above and in every living doc,
not hidden.

**Explicit, honestly-documented gaps (not hidden):**
- Zero live AWS execution — see "Validation performed" above and
  `deployment/VERIFICATION.md` for the full breakdown.
- No S3 object-storage backend — complaint photos still use the Phase 6
  `LocalStorageService`, backed by the EC2 host's own EBS volume for
  this pilot; a real, tracked open item (`deployment/README.md`'s
  "Object storage" section), not an oversight.
- RDS TLS is encrypted but not certificate-chain-verified
  (`verifyServerCertificate=false`) — full pinning needs a bundled CA
  truststore, left open.
- Only infrastructure-level monitoring/alerting built (EC2 status check,
  RDS CPU/storage) — the SRS's four application-level alert conditions
  need backend/ai-service metric-emission code that doesn't exist yet;
  out of this phase's `deployment/`-only scope.
- No automated EBS snapshot schedule for the app host's storage volume.
- No signed Flutter release build — carried forward from Phase 21;
  `build_release.sh` can produce an unsigned/debug-signed APK for pilot
  sideload distribution only.
- Single-instance, single-AZ, no ALB/ASG/ECS — a deliberate match to the
  SRS's low-cost-infrastructure pilot constraint, with a documented
  scaling path (`deployment/README.md`'s "Scaling beyond the pilot") for
  if/when actually warranted.

**Diff audit against Phase 21:** confirmed additive-only — `diff -rq`
against the Phase 21 baseline reports changes in exactly two
pre-existing files (`.gitignore` — additive Terraform-ignore block;
`backend/src/main/resources/application-prod.yml` — additive TLS JDBC
override, described above) plus the four living docs, one new file
under the existing `.github/workflows/` directory
(`deploy-aws.yml`), and otherwise only new files under the new
`deployment/` directory. Zero files deleted. Zero backend `main/`
Java classes, ai-service `app/`, `database/migrations/`, flutter
`lib/`, docker Dockerfiles/compose, or Phase 21's own five workflow
files touched. `postman/` confirmed untouched.

**jannet-ai-phase22.zip:** packaged and integrity-checked (`unzip -t` +
round-trip extraction diff against the source tree).

---

## Phase 21 — GitHub Actions / CI-CD Automation — COMPLETE

**Component built:** `.github/workflows/` (per ARCHITECTURE.md Section
8's Phase -> Component map: `21 | .github/workflows/`). No application
source changed this phase — see the two small, documented build-tooling
exceptions below.

**Deliverables:**
- `.github/workflows/backend-ci.yml` — `mvn clean verify` (JDK 21) on
  every change under `backend/`/`database/migrations/`: compiles, runs
  the full Phase 20 JUnit5/Mockito/MockMvc/`@DataJpaTest` suite against
  H2-in-MySQL-mode (no external MySQL service container needed),
  packages `backend.jar`, publishes Surefire + JaCoCo reports as
  artifacts.
- `.github/workflows/ai-service-ci.yml` — installs
  `ai-service/requirements.txt` (plus the system `tesseract-ocr`/
  `libgl1`/`libglib2.0-0` packages the app's own imports need, mirroring
  `docker/Dockerfile.ai-service`), runs the full Phase 20 pytest suite
  with coverage, publishes JUnit + coverage XML as artifacts. No `.env`
  needed — every `Settings` field in `app/config.py` already has a safe
  default.
- `.github/workflows/flutter-ci.yml` — `flutter pub get` -> `flutter
  analyze` -> `flutter test --coverage` -> `flutter build apk --debug`
  on every change under `flutter/`; publishes coverage + the debug APK
  as artifacts.
- `.github/workflows/docker-build.yml` — builds both
  `docker/Dockerfile.backend` and `docker/Dockerfile.ai-service` images
  (first time either has ever actually been built — both carried a "NOT
  VERIFIED: no Docker daemon" comment since Phase 18), scans each with
  Trivy (report-only for now, uploaded to the Security tab), then brings
  the full `docker-compose.yml` stack up with freshly-`openssl rand`-
  generated, run-scoped credentials and confirms both `/health` and
  `/actuator/health` report healthy before tearing the stack down.
- `.github/workflows/codeql.yml` — GitHub CodeQL SAST for Java and
  Python, on every relevant change plus a weekly schedule.
- `.github/workflows/release-image-publish.yml` — on a pushed
  `v*.*.*` tag or manual dispatch only, builds and publishes both
  service images to GHCR under that version tag. Publishes an image;
  does **not** deploy it anywhere — see that file's own header comment
  for the Phase 21/22 boundary this deliberately respects.
- `.github/dependabot.yml` — weekly automated dependency-update PRs for
  Maven, pip, pub, the Dockerfiles, and the workflows above.
- `.github/workflows/README.md` — full documentation of triggers, jobs,
  secret/environment handling, CI failure handling, and the boundaries
  this phase deliberately did not cross.
- Two small, documented, additive build-tooling changes needed *for* the
  above (not new application behavior): `backend/pom.xml` gained a
  test-phase-bound `jacoco-maven-plugin` (coverage report generation for
  `backend-ci.yml`'s artifact upload), and `ai-service/requirements.txt`
  gained a test-scope `pytest-cov` dependency (same reason for
  `ai-service-ci.yml`). Neither adds or changes any `main`/application
  source; see each file's own inline comment for the full rationale.
- `PROJECT_PROGRESS.md`, `PROJECT_INTEGRATION.md`, `ARCHITECTURE.md` —
  all updated with Phase 21's scope, decisions, and honestly-documented
  limits.

**Validation performed (this workspace, no live GitHub Actions runner
available):**
- Every workflow YAML file parsed successfully with `yaml.safe_load`
  (Python) — confirmed syntactically valid YAML. (`on:` parses as the
  boolean key `True` under PyYAML's YAML-1.1 rules — a well-known
  cosmetic quirk of parsing GitHub Actions files with a generic YAML
  library; GitHub's own workflow parser reads `on` as the literal string
  key it's documented to be. Not a real defect.)
- Every third-party Action reference (`actions/checkout@v4`,
  `actions/setup-java@v4`, `actions/setup-python@v5`,
  `actions/upload-artifact@v4`, `actions/download-artifact@v4`,
  `docker/setup-buildx-action@v3`, `docker/build-push-action@v6`,
  `docker/login-action@v3`, `subosito/flutter-action@v2`,
  `github/codeql-action@v3`, `aquasecurity/trivy-action@0.35.0`)
  confirmed to actually exist at that tag via `codeload.github.com`
  (HTTP 200), since `api.github.com` was rate-limited. The initial
  `trivy-action@0.28.0` guess did not exist (404) and was corrected to
  the real, confirmed-current `0.35.0` tag.
- `aquasecurity/trivy-action`'s `action.yaml` fetched directly and
  compared line-by-line against this phase's `image-ref`/`format`/
  `output`/`severity`/`exit-code` inputs — all confirmed to be real,
  correctly-named inputs of that action, not guessed names.
- `github/codeql-action/upload-sarif`'s `action.yml` fetched and
  confirmed `sarif_file` accepts a directory (not just a single file) —
  supports this workflow's `sarif_file: .` usage picking up both Trivy
  SARIF outputs at once.
- All six `.env` variable names the `docker-build.yml` smoke-test job's
  `sed` commands target (`MYSQL_ROOT_PASSWORD`, `DB_PASSWORD`,
  `JWT_SECRET`, `AI_SERVICE_API_KEY`, `BOOTSTRAP_SUPER_ADMIN_MOBILE`,
  `BOOTSTRAP_SUPER_ADMIN_PASSWORD`) cross-checked character-for-character
  against the real `.env.example` — all six exist with those exact
  names.
- **NOT VERIFIED** (no GitHub Actions runner, Docker daemon, Maven
  Central reach, or Flutter SDK available in this sandbox to actually
  execute a workflow end-to-end): whether `backend-ci.yml`'s `mvn clean
  verify` and `flutter-ci.yml`'s `flutter test`/`flutter build apk`
  actually pass on Phase 20's inherited, never-yet-executed test/build
  code; whether `docker-build.yml`'s compose smoke test's health-check
  timeouts are well-calibrated on a real runner; whether CodeQL's
  `java-kotlin` autobuild successfully locates and builds
  `backend/pom.xml` unassisted. These are real first-run risks
  inherited from every prior phase's own "NOT VERIFIED" caveats, not new
  Phase 21 defects — see "Next phase" below for how to triage a failure
  found here.

**Genuine finding this phase:** none — no live runner meant no chance to
execute anything and find a real defect (matching the pattern already
seen at each of the earlier "written but not run" phases). If a workflow
does fail the first time it actually runs, the correct interpretation
per the "Validation performed" note above is worked out there, not
assumed to be a CI misconfiguration by default.

**Scope boundaries respected:** zero backend `main/`/`test/`, ai-service
`app/`/`tests/`, `database/migrations/`, flutter `lib/`/`test/`, or
`docker/` (Dockerfiles/compose file themselves) source changed.
`postman/`'s three files untouched — deliberately not modified to add
token-chaining test scripts that would have let `docker-build.yml`
attempt a full Newman regression run; see that workflow's own comment
and `.github/workflows/README.md`'s "What this phase deliberately did
NOT build" section for the reasoning. `deployment/` remains untouched
and empty — reserved for Phase 22 per ARCHITECTURE.md Section 8.

**Explicit, honestly-documented gaps (not hidden):**
- No Postman/Newman contract-level regression test runs against the live
  `docker compose` stack — only both services' own health endpoints are
  checked. See above for why.
- Trivy's image scan is report-only (`exit-code: 0`), not a hard-failing
  gate, pending a real run history to baseline expected findings against.
- No OWASP `dependency-check-maven`-style hard vulnerability gate — it
  now requires a provisioned NVD API key this project has never had;
  CodeQL + Dependabot cover overlapping ground without that dependency.
- No signed Flutter release build — `flutter-ci.yml` builds unsigned
  debug APKs only; a real release keystore is a Phase 22 concern.
- None of this phase's workflows have ever actually been run by GitHub
  Actions — see "Validation performed" above for the full first-run risk
  list.

**Diff audit against Phase 20:** confirmed additive-only — `diff -rq`
against the Phase 20 baseline reports changes in exactly two pre-existing
files (`backend/pom.xml`, `ai-service/requirements.txt` — both small,
documented, build-tooling-only additions with nothing removed) plus the
four living docs, and otherwise only new files under the new
`.github/` directory. Zero files deleted. Zero backend/ai-service/
flutter/`database/migrations`/docker *application* source touched.
`deployment/` and `postman/` confirmed untouched (no diff at all).

**jannet-ai-phase21.zip:** packaged and integrity-checked (`unzip -t` +
round-trip extraction diff against the source tree).

---

## Phase 20 — Testing — COMPLETE

**Component built:** `all — testing` (per ARCHITECTURE.md Section 8's
Phase -> Component map). No application source changed — test code, one
test-scope dependency, one test config file, and one standalone
validation script only.

**Deliverables:**
- **ai-service** (Python) — 4 new pytest files (`test_api_contract.py`,
  `test_api_deps.py`, `test_image_fetch.py`, `test_yolo_service.py`) plus
  a bug fix in the pre-existing `test_preprocessing.py`. **Actually
  executed: 74/74 tests passing** — this workspace's network allowlist
  now includes `pypi.org`, a first for this project (Maven Central is
  still unreachable; the heavy `torch`/`ultralytics`/
  `google-generativeai` dependencies still can't be installed here due
  to disk-space limits).
- **backend** (Java/Spring Boot) — 10 new JUnit5/Mockito/MockMvc/
  `@DataJpaTest` test classes covering the complaint state machine, the
  Phase 13 department-scope security fix, auth lockout/MFA, JWT
  issuance/validation, the auth filter, notification retry logic,
  department/officer routing, one controller-slice test, and one
  repository test against a real Flyway-migrated H2 schema. New `h2`
  test-scope dependency in `pom.xml` and new
  `src/test/resources/application-test.yml`. **NOT executed** — no
  Maven Central reach in this workspace; manually validated (brace/paren
  balance, method-signature/field-name cross-checks against live
  source).
- **flutter** — 4 new widget/unit test files (`ComplaintStatus` enum,
  `Complaint`-model JSON parsing, `StatusBadge` widget, `LoginScreen`
  widget). **NOT executed** — no `flutter`/`dart` SDK in this workspace;
  manually validated.
- **database** — new `database/validation/validate_migrations.py`.
  **Actually executed**: confirmed all 18 migrations are sequentially
  numbered with correct FK dependency ordering, valid SQL syntax, and
  zero entity/schema column-name drift.
- `PROJECT_PROGRESS.md`, `PROJECT_INTEGRATION.md`, `ARCHITECTURE.md` —
  all updated with Phase 20's scope, findings, and decisions.

**Validation performed:** see PROJECT_PROGRESS.md's COMPLETED section for
the full breakdown of what was actually executed (ai-service pytest
suite, migration validation script — both run for real, with real
output captured) versus what was written and manually validated only
(the entire backend JUnit suite, all Flutter tests) due to this
workspace's persistent lack of Maven Central reach and Flutter SDK.

**Genuine finding this phase:** running the ai-service test suite for
the first time (rather than only reviewing it) surfaced one real
test-fixture bug — `test_preprocessing.py`'s
`test_flags_dark_image_without_rejecting` was asserting the wrong
quality-gate branch due to a fixture that inadvertently crushed image
contrast along with brightness. Fixed and documented in
`PROJECT_INTEGRATION.md` Section 6. No bugs were found in
backend/database/flutter application source this phase (none of that
code could be executed to find any).

**Scope boundaries respected:** zero backend `main/`, ai-service `app/`,
`database/migrations/`, or flutter `lib/` application source changed —
testing only. `postman/`'s three files untouched (no live server existed
to run them against and find a real contract mismatch). `docs/`
(still unassigned to any phase) untouched.

**Explicit, honestly-documented gaps (not hidden):**
- Backend: admin/settings services, dashboard/analytics services,
  `EscalationSchedulerService`, and ten of eleven controllers have no
  dedicated test file yet.
- Flutter: no mocking dev-dependency exists, so no screen's happy-path
  HTTP submit flow is tested — only what's reachable without one (model
  parsing, static widget trees, navigation, and the network-failure
  error-handling path).
- Neither the backend JUnit suite nor the Flutter test suite has ever
  actually been executed in this project's history — both remain
  manually-validated-only pending a Maven-enabled/Flutter-SDK-enabled
  environment.

**Diff audit against Phase 19:** confirmed additive-only — see this
phase's own diff-audit output captured before packaging (new files only
under `backend/src/test/`, `ai-service/tests/`, `flutter/test/`,
`database/validation/`, plus the four living docs, `backend/pom.xml`,
and `backend/src/test/resources/application-test.yml`; the only modified
pre-existing file is `ai-service/tests/test_preprocessing.py`'s one
fixture fix; zero files deleted; zero backend/database/ai-service/
flutter/docker *application* source touched).

**jannet-ai-phase20.zip:** packaged and integrity-checked (`unzip -t` +
round-trip diff against the source tree).

---

## Next phase

**Phase 23** has not been started. Per ARCHITECTURE.md Section 8, check
that section's Phase -> Component map for Phase 23's named component
before assuming its scope — do not guess it from this note alone. When
Phase 23 begins:
1. Read this file, `PROJECT_PROGRESS.md`, `PROJECT_INTEGRATION.md`, and
   `ARCHITECTURE.md` in full first — non-negotiable, per this project's
   own established convention since Phase 1.
2. Inspect `jannet-ai-phase22.zip` (or its extracted contents) before
   writing any file.
3. First real thing to check: whether Phase 22's Terraform config has
   actually been applied against a real AWS account yet, and if so,
   whether the EC2 bootstrap script completed successfully and whether
   the inherited V1-V18+ Flyway migrations finally ran cleanly against a
   real RDS instance (this project's single longest-standing gap, since
   Phase 3). If the bootstrap or migrations failed, that is very likely
   a genuine defect surfacing for the first time — see
   `deployment/VERIFICATION.md`'s own triage note — and should be
   resolved before or alongside Phase 23's own work, not silently worked
   around.
4. Phase 22's explicitly-documented gaps (no S3 storage backend, no
   application-level CloudWatch alerts, no automated EBS snapshots, no
   signed Flutter release, no certificate-chain-verified RDS TLS) are
   not automatically Phase 23's job — re-evaluate each against Phase
   23's actual named scope in ARCHITECTURE.md rather than assuming it
   inherits all of them.

## Session resumption note

Phase 22 is fully complete — there is no in-progress task to resume. A
"CONTINUE" sent from here should be treated as "begin Phase 23" only if
explicitly instructed; otherwise treat Phase 22's zip as the final state
to inspect.
