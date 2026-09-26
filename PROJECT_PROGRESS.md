CURRENT PHASE: Phase 23 — Final Integration Testing (FINAL PHASE — COMPLETE)
CURRENT MODULE: all — final integration verification (ARCHITECTURE.md
Section 8's Phase -> Component map: "23 | all — final integration
testing"). This phase touched application source in exactly one place,
for a genuine defect this phase's own real-execution validation found
(see COMPLETED and DECISIONS THIS PHASE below):
database/migrations/V6__create_complaints.sql had one CHECK constraint
removed because MySQL 8 rejects it outright. Every other line of
backend/ai-service/flutter/docker/.github source is byte-for-byte
unchanged from the Phase 22 baseline — confirmed by a full `diff -rq`
against the untouched `jannet-ai-phase22.zip` extraction, captured
verbatim in PHASE_HANDOFF.md's "Diff audit against Phase 22."
CURRENT TASK: Complete — none in progress. THIS IS THE FINAL PHASE. There
is no Phase 24.


---

PHASE 23 SCOPE DETERMINATION (done before any file was touched):

Read PHASE_HANDOFF.md, this file (Phase 22 body), PROJECT_INTEGRATION.md,
and ARCHITECTURE.md in full first, per this project's own established
convention since Phase 1. ARCHITECTURE.md Section 8 names this phase's
component plainly: `23 | all — final integration testing`. PHASE_HANDOFF.md's
own "Next phase" note (from Phase 22) told this phase to check, as its
very first real action, whether Phase 22's Terraform had been applied and
whether the inherited V1-V18 Flyway migrations had finally run against a
real MySQL instance — that became this phase's first and highest-priority
action once tooling availability was confirmed (see below), not an
afterthought.

Confirmed before writing anything:
- `jannet-ai-phase22.zip` was extracted and inspected in full (top-level
  structure, all four living docs read completely) before any edit.
- This sandbox's toolchain was re-checked from scratch rather than assumed
  from any prior phase's note (tool availability has changed phase-to-phase
  throughout this project's history — Phase 20 found pypi.org reachable
  for the first time, Phase 22 found no AWS reach at all): `java` (21,
  pre-installed), `python3` (3.12, pre-installed), `mvn` (installable via
  `apt-get install maven` — a first for this project), `mysql-server`
  (installable via `apt-get install mysql-server`, real MySQL 8.0.46 — a
  first for this project), `docker`/`dockerd` (installable as a package
  but a running daemon was not attempted — out of scope to chase for a
  verification-only phase once real MySQL execution, the project's single
  highest-priority gap, was confirmed reachable instead), `terraform`
  (still not installable — HashiCorp's registry/release domains are not
  on this workspace's network allow-list), `flutter`/`dart` SDK (still not
  installable — no reachable distribution channel on the allow-list).
  Maven Central (`repo.maven.apache.org`) itself remains unreachable even
  with `mvn` installed — confirmed by a real `mvn compile` attempt that
  failed exactly as expected (cannot resolve `spring-boot-starter-parent`
  in offline mode) — so a full backend compile/test run remains NOT
  VERIFIED, same as every phase since Phase 3, not a regression introduced
  this phase.
- Because real MySQL 8 finally became reachable for the first time in this
  project's history, running the full, real Flyway migration set against
  it was treated as this phase's single highest-priority validation
  action, exactly as Phase 22's own handoff note anticipated — not a nice-
  to-have alongside everything else.
- `JanNet_AI_SRS_BRD_FRS.docx` was re-scanned via `python-docx` (666
  paragraphs; full heading/section map extracted) to confirm the 21-point
  verification checklist supplied with this phase's instructions maps onto
  real, already-built SRS modules and screens rather than inventing new
  ones to check against — it does, one-to-one (Sections 13/14/17/18/19/20
  cover exactly the 21 checklist items).
- No new feature work was in scope. Phase 23's job is verification and
  the fix of genuine integration defects that verification surfaces, per
  this phase's own explicit instructions ("do not invent missing
  features," "fix only issues required for final project completion").

COMPLETED:

**Real execution, for the first time in this project's history:**
- Installed MySQL 8.0.46 (matching `docker-compose.yml`'s pinned
  `mysql:8.0` image and `rds.tf`'s Phase 22 Terraform version) and ran
  every one of the 18 Flyway migrations (`V1__create_wards.sql` through
  `V18__widen_status_history_actor_type.sql`) against it, in order, via
  the real `mysql` CLI. **Found a genuine defect on the very first real
  run**: `V6__create_complaints.sql` failed with MySQL error 3823 —
  InnoDB refuses to allow a column that carries a foreign key's
  referential action (`fk_complaints_parent_complaint ... ON DELETE SET
  NULL`, a self-referencing FK on `parent_complaint_id`) to also
  participate in a CHECK constraint (`chk_complaints_not_self_parent`).
  This is a real MySQL/InnoDB platform limitation that no amount of
  static review (brace/paren balance, manual SQL reading — this
  project's only prior validation method for this file, across 22
  phases) could have caught, because it only manifests against a real
  server. **Fixed** by removing the CHECK constraint and documenting why
  directly in the migration file — confirmed safe because the identical
  rule is already enforced at the application layer
  (`ComplaintService`'s DUPLICATE-decision branch explicitly rejects
  `parentComplaintId.equals(complaintId)` with an
  `InvalidStateTransitionException` before any row is written), so the
  CHECK was pure defense-in-depth, not the only guard against a
  self-referencing complaint. **Re-ran all 18 migrations clean** after
  the fix: zero errors, all 15 expected tables created
  (`wards`, `departments`, `users`, `locations`, `complaints`, `images`,
  `predictions`, `budget`, `status_history`, `notifications`,
  `audit_logs`, `settings`, `routing_rules`, `otp_verifications`,
  `refresh_tokens`). This closes this project's single longest-standing
  carried-forward gap, flagged unchanged in every phase's living docs
  since Phase 3.
- Re-ran `database/validation/validate_migrations.py` (Phase 20) against
  the fixed migration set: passes clean (18 migrations, 15 entities, no
  version-sequence/FK-ordering/SQL-syntax problems, no entity/schema
  column-name drift).
- Installed the ai-service's non-heavyweight real dependencies
  (`fastapi`, `pydantic`, `pydantic-settings`, `httpx`,
  `python-multipart`, `numpy`, `Pillow`, `opencv-python-headless`,
  `pytest`, `pytest-asyncio`, `pytest-cov`) from `pypi.org` and ran the
  **real** pytest suite: **74/74 tests passing**, matching Phase 20's own
  first real run exactly — confirms the ai-service test suite still
  passes, for real, at the Phase 22 baseline. `ultralytics`,
  `google-generativeai`, and `pytesseract` remain unnecessary for a real
  test run because `yolo_service.py`/`gemini_service.py`/`ocr_service.py`
  import them lazily/optionally at runtime (Phase 7/9/10's own documented
  design), not at module load — confirmed by the successful `import
  app.main` this test run required.
- `python3 -m py_compile` against every `.py` file under `ai-service/`:
  zero syntax errors — a real compile-level check, not the balance-check
  substitute used for backend Java/Flutter Dart (see below).
- `bash -n` against every shell script in the project (`database/scripts/
  backup.sh`\|`restore.sh`, `deployment/scripts/deploy.sh`\|`rollback.sh`\|
  `health-check.sh`, `deployment/flutter/build_release.sh`) plus
  `deployment/scripts/ec2-user-data.sh.tpl` with its Terraform
  `${...}` interpolations mechanically stripped first: zero syntax
  errors, same result as every phase since these scripts were written.
- `yaml.safe_load` against all six `.github/workflows/*.yml` files,
  `.github/dependabot.yml`, and `docker/docker-compose.yml`: all parse
  clean. `deployment/docker-compose.prod-override.yml` needed a
  Compose-spec-aware loader (its `build: !reset null` / `depends_on:
  !reset` lines use the real Docker Compose merge-spec `!reset` tag,
  which plain YAML doesn't recognize) — confirmed structurally correct
  with a custom PyYAML constructor for that tag; this is the same
  category of parser-quirk-not-a-defect Phase 21 already documented for
  the `on:` boolean key. Both Postman JSON files
  (`postman/JanNet_AI.postman_collection.json`,
  `postman/JanNet_AI_Local.postman_environment.json`) parse clean via
  `json.load`.

**Manual/static validation (this project's established method for
components no real compiler/SDK exists for in this sandbox):**
- Brace/paren balance check (comments and string literals stripped) on
  all 195 backend Java files (`src/main/java` + `src/test/java`): zero
  imbalances.
- Brace/paren/bracket balance check (comments and string literals
  stripped, including triple-quote-style handling) on all 55 Flutter Dart
  files (`lib/` + `test/`): zero imbalances.
- Brace balance check on all 10 Terraform `.tf` files under
  `deployment/aws/terraform/`: zero imbalances.
- Full endpoint cross-check: extracted every `@GetMapping`/
  `@PostMapping`/`@PutMapping`/`@PatchMapping` from all 11 backend
  controllers (51 endpoints) plus all 5 `ai-service` routes (4 `/api/v1/
  ai/*` + `/health`) and compared one-for-one against
  `postman/JanNet_AI.postman_collection.json`'s 56 requests: **exact
  match, 56 = 56**, reconfirming Phase 19's original one-for-one claim
  still holds unchanged at the Phase 22 baseline.
  **Superseded (audit GAP-044, fix Phase 06):** by the Sep 2026 forensic
  audit the collection had drifted (18 backend endpoints missing, so this
  "56 = 56" no longer held). The collection now has 109 requests (fix Phase 07) in 18
  folders: every backend controller mapping (96 incl. the Phase 06-07
  additions) is present, checked by an automated mapping-vs-collection
  diff, plus the ai-service internal routes. See postman/README.md.
- Reviewed `SecurityConfig.java`'s full `authorizeHttpRequests` chain:
  every business path group (`/auth/**` permitAll; `/users/me/**`,
  `/wards/**`, `/complaints/**`, `/departments/**`, `/admin/**`,
  `/notifications/**` authenticated()) is either explicitly listed or
  falls through to the `anyRequest().authenticated()` fail-closed
  default (`/dashboard/**` has no explicit rule and relies on that
  default, which is itself the file's own documented design — not a gap).
  No endpoint is reachable unauthenticated outside the documented
  `/auth/**`/`/actuator/health`/`/actuator/info`/`/swagger-ui/**`
  allow-list.
- Confirmed all eight Flutter `*_api.dart` client files
  (`auth_api.dart`, `user_api.dart`, `complaints_api.dart`,
  `department_api.dart`, `dashboard_api.dart`, `notification_api.dart`,
  `admin_api.dart`, `settings_api.dart`) exist and correspond to a real
  backend controller module; ward lookup is called from `auth_api.dart`
  (registration flow), not a separate client, matching Phase 17's own
  documented Ward-at-registration design.

**Genuine integration issues found and fixed this phase:**
1. `database/migrations/V6__create_complaints.sql` — the MySQL 8
   CHECK-constraint-vs-self-referencing-FK defect described in detail
   above. The only application-source change this phase made.
2. `deployment/{aws` — a stray, empty, malformed directory left over
   from Phase 22's own packaging (a shell brace-expansion command that
   ran without expanding, most likely `mkdir -p
   deployment/{aws,terraform,scripts,nginx,ssm}` executed under `sh`
   rather than `bash`, or with the brace pattern quoted) — confirmed
   empty (zero files) via a recursive `find` before deletion, so nothing
   was lost by removing it. Deleted.

**NOT VERIFIED (honestly documented, not silently assumed complete):**
- A full backend `mvn clean verify`/compile — Maven itself is now
  installable in this sandbox (a first), but Maven Central
  (`repo.maven.apache.org`) is not on the network allow-list, confirmed
  by a real attempted `mvn compile` failing exactly as expected on the
  unresolvable `spring-boot-starter-parent` parent POM. Unchanged since
  Phase 3 — not a regression, a persistent environment constraint.
- Flutter `pub get`/`analyze`/`test`/`build` — no Flutter/Dart SDK
  distribution channel reachable from this sandbox's allow-list.
  Unchanged since this project's first Flutter phase (6).
- `terraform validate`/`plan`/`apply` — no HashiCorp registry/release
  domain reachable. Unchanged since Phase 22.
- A real Docker build/`docker compose up` — `docker.io` is installable
  via `apt`, but standing up and validating a working Docker daemon
  inside this sandbox (nested-containerization concerns) was not
  attempted this phase; real MySQL execution was correctly prioritized
  instead as this project's actual single highest-priority gap per
  Phase 22's own handoff note. Unchanged since Phase 18/21.
- Live AWS Terraform apply, live GitHub Actions execution, live Postman/
  Newman run against a running server, a real signed Flutter release
  build — all unchanged from Phase 22/21/19/21 respectively; none of the
  infrastructure those require exists in or is reachable from this
  sandbox.
- The Postman collection was cross-checked structurally (JSON validity,
  one-for-one endpoint-count/path match against live controller source)
  but was not executed against a running server with Newman — no live
  server was stood up this phase (would have required the still-
  unreachable Maven Central to build the backend). Unchanged since
  Phase 19.

DECISIONS THIS PHASE:
- Documented in full in `PROJECT_INTEGRATION.md` Section 6 (new Phase 23
  entries): the `chk_complaints_not_self_parent` removal and why it's
  safe, the stray-directory deletion, and the final verification-scope
  boundary (fix only genuine defects verification surfaces; no new
  features).
- Did not attempt to stand up a Docker daemon or chase a live AWS/
  Terraform/GitHub-Actions run — real MySQL execution was the correct,
  higher-priority use of this phase's one genuinely new tooling
  capability (an installable `mysql-server` package), per Phase 22's own
  explicit handoff instruction to treat that as "the priority validation
  moment it's been waiting for across 22 phases."
- Did not rework the removed CHECK constraint into a `BEFORE INSERT/
  UPDATE` trigger as an alternative fix — the application-layer guard
  already covers the same rule with a clearer error message
  (`InvalidStateTransitionException` with a human-readable reason) than a
  raw trigger `SIGNAL` would, and this project's own established
  principle (see ARCHITECTURE.md's Key learnings) is that a DB constraint
  duplicating an application-layer guard can be safely removed once
  that's documented — exactly this case.

FINAL PROJECT STATUS: All 21 verification checkpoints from this phase's
own instructions are covered (see PHASE_HANDOFF.md's Phase 23 entry for
the itemized pass/NOT-VERIFIED breakdown per checkpoint). Diff audit
against Phase 22 confirms exactly two changes: the one-file migration fix
and the one stray-directory deletion — zero unintended modifications,
zero deletions of real project content. `jannet-ai-phase23.zip` is the
final project deliverable. **There is no Phase 24.**

---

## Post-Phase-23 maintenance: complete Flutter UI redesign

Patch: `jannet-ai-complete-ui-redesign.patch` (Flutter + docs only; no backend, database, AI-service or API change).

- **Design system.** New tokens and theme (`lib/core/theme/`) and shared components (`lib/core/widgets/jan_*.dart`). See `docs/UI_DESIGN_SYSTEM.md`.
- **Screens restyled.** Every existing screen now uses the reference visual language (`UI Photo.zip`):
  - auth (login, register, OTP, MFA, forgot and reset password)
  - the citizen shell and its screens
  - notifications, settings and privacy/terms
  - officer, verification, department head and admin shells and screens
- **Logic and API calls are unchanged.**
  - Registration and OTP still use the real `/auth` endpoints, with no hard-coded or simulated OTP.
  - Image capture, preview, remove and replace are still wired to `image_picker` and multipart upload.
- **Deliberately not added.**
  - No onboarding or standalone profile screen, because neither existed. The web auth brand panel carries the onboarding messages, and Settings shows a profile header from `GET /users/me`.
  - No notification read/unread state (the backend has no such field).
  - No community map (the endpoint returns ward aggregates only).
- **Verification.** No Flutter SDK was available in the working sandbox.
  - `flutter analyze`, `flutter test` and `flutter build web`/`apk` were **NOT EXECUTED**.
  - Static checks that were executed:
    - tree-sitter Dart parse of every changed file
    - a named/required-parameter check against the Flutter 3.47.1 framework source
    - a member-name check and an import resolution check
    - `tool/check_accessibility.py`, with 0 violations
  - New widget tests are in `test/core/widgets/jan_components_test.dart`. They were written, but NOT EXECUTED.

---

## Post-UI-redesign gap fix

Patch: `jannet-ai-post-ui-gap-fix.patch`. Flutter and docs only; no backend, database or AI-service change. Full classification and rationale: `docs/POST_UI_GAP_FIX.md`.

- **Fixed:**
  - **Complaint and after-photo uploads.** They were sent as `application/octet-stream` and so rejected by `ComplaintService.validatePhoto`. Photos now go as bytes with their real content type.
  - **Web photo flow.** It is real now: browser file chooser, mobile-browser camera, preview, remove and replace, all without `dart:io`.
  - **Ward/Area at registration (SRS 16.1).** Loaded from the existing public `GET /api/v1/public/wards` endpoint.
  - **My Profile (SRS 15.1 profile management and reputation score).** Uses the existing `GET` and `PUT /users/me`.
  - **First-launch onboarding.** Taken from the approved design reference; client-only.
- **Documented, not built:**
  - A geographic community map (class E: no map package, provider or ward geometry).
  - Notification read/unread state (class D: no SRS 20.5 contract or schema support).
  - Photos on complaint list cards (class D: the list DTO deliberately omits images).
  - OTP login (class D: no requirement, no endpoint).
- **Verification:** static checks only.
  - `flutter analyze`, `flutter test` and the builds were NOT EXECUTED (no SDK).
  - New tests: `test/core/api/upload_file_test.dart` and `test/features/onboarding/onboarding_screen_test.dart`.
