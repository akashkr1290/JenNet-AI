# JanNet AI - CI/CD (`.github/`)

Phase 21 deliverable. Per `ARCHITECTURE.md` Section 8's Phase -> Component
map (`21 | .github/workflows/`), this directory implements CI/CD
automation for the four locked-stack components already built in Phases
1-20. **No application source changed this phase** — see
`PHASE_HANDOFF.md`'s Phase 21 entry for the exact diff.

## Why this phase matters more than most

Every prior phase's own documentation carries some version of the same
caveat: *"NOT VERIFIED — no live JDK/Maven/MySQL/Flutter SDK/Docker
daemon in this workspace."* A GitHub Actions runner is the first
environment in this project's history with real Maven Central reach, a
real Flutter SDK, a real Docker daemon, and enough disk to install
`ultralytics`/`torch`. The workflows below are this project's first
chance to find out whether Phase 20's backend JUnit suite and Flutter
test suite — written and only ever manually validated — actually compile
and pass. If a workflow's first real run surfaces a failure in that
inherited code, that is a genuine Phase 20-scope defect being found for
the first time, not a Phase 21 defect — see `PROJECT_INTEGRATION.md`
Section 6's Phase 21 entry for how that should be triaged.

## Workflows

| File | Triggers on | What it does |
|---|---|---|
| `workflows/backend-ci.yml` | changes under `backend/`, `database/migrations/` | `mvn clean verify` (JDK 21) — compiles, runs the full JUnit5/Mockito/MockMvc/`@DataJpaTest` suite against H2-in-MySQL-mode (no external MySQL needed), packages `backend.jar`, publishes Surefire + JaCoCo coverage reports as artifacts. |
| `workflows/ai-service-ci.yml` | changes under `ai-service/` | Installs `requirements.txt` (incl. system `tesseract-ocr`/`libgl1` packages the app imports need), runs the full pytest suite with coverage, publishes JUnit + coverage XML as artifacts. No `.env` needed — every `Settings` field in `app/config.py` has a safe default. |
| `workflows/flutter-ci.yml` | changes under `flutter/` | `flutter pub get` → `flutter analyze` → `flutter test --coverage` → `flutter build apk --debug`, publishes coverage + the debug APK as artifacts. Debug build only — a signed release build needs a keystore that belongs to Phase 22. |
| `workflows/docker-build.yml` | changes under `docker/`, `backend/`, `ai-service/`, `database/migrations/` | Builds both `docker/Dockerfile.backend` and `docker/Dockerfile.ai-service` images, scans each with Trivy (report-only — see the workflow's own comment on why it doesn't hard-fail yet), then brings the full `docker-compose.yml` stack up with freshly-generated, run-scoped credentials and confirms both `/actuator/health` and `/health` report healthy before tearing the stack down. |
| `workflows/codeql.yml` | changes under `backend/`, `ai-service/`; also weekly on a schedule | GitHub CodeQL static analysis (SAST) for Java and Python. Findings appear under the repo's Security > Code scanning tab. |
| `workflows/release-image-publish.yml` | a pushed `v*.*.*` tag, or manual dispatch | Builds and publishes both service images to GHCR under that version tag. **Publishes an image; does not deploy it anywhere** — see the workflow's own header comment for the Phase 21/22 boundary this deliberately respects. |
| `dependabot.yml` (repo root) | n/a (Dependabot's own weekly schedule) | Automated dependency-update PRs for Maven, pip, pub, Docker base images, and the workflows above. |

## Secrets and environment handling

- **`backend-ci.yml` and `ai-service-ci.yml` need zero secrets.** The
  backend test profile (`backend/src/test/resources/application-test.yml`)
  and the ai-service `Settings` defaults were both built (Phase 20 /
  Phase 7) specifically so their respective test suites never require a
  real credential.
- **`docker-build.yml`'s smoke-test job** generates its own
  `MYSQL_ROOT_PASSWORD`/`DB_PASSWORD`/`JWT_SECRET`/`AI_SERVICE_API_KEY`
  with `openssl rand` at the start of every run — freshly created,
  run-scoped, and discarded with the runner. Nothing is hard-coded and
  nothing is a repository secret, because this job never needs to talk to
  anything outside its own ephemeral runner.
- **`release-image-publish.yml`** uses only the ambient, auto-rotated
  `secrets.GITHUB_TOKEN` GitHub Actions already provides for pushing to
  GHCR — no separate registry credential was created.
- **No workflow in this phase reads `GEMINI_API_KEY`, SMTP credentials,
  or any other real third-party secret** — none of Phase 21's jobs call
  out to a real Gemini/SMTP/SMS endpoint, so none of them need one.
  Should a future phase add a CI job that does, it must be added as a
  GitHub Actions repository/environment secret (Settings > Secrets and
  variables > Actions) and referenced via `${{ secrets.NAME }}` — never
  committed to any file in this repository, per this phase's explicit
  "do not hard-code secrets" instruction.

## CI failure handling

- Every job fails the workflow (and blocks a PR, once branch protection
  requires these checks — see below) on a non-zero exit code from its
  build/test/health-check step; nothing silently continues past a real
  failure.
- Test/coverage reports are uploaded with `if: always()` so a failed
  run's diagnostics are still downloadable, not lost with the runner.
- `docker-build.yml`'s smoke-test job dumps `docker compose logs` on
  failure before tearing the stack down, so a health-check timeout is
  debuggable without needing to reproduce it locally first.
- Superseded runs on the same branch/PR are cancelled
  (`concurrency: cancel-in-progress: true`) so a rapid sequence of pushes
  doesn't queue up redundant, already-stale CI minutes.
- Trivy's image scan is intentionally report-only (`exit-code: 0`) rather
  than a hard gate for now — see `docker-build.yml`'s own comment for why,
  and treat tightening this as a natural, tracked follow-up once this
  workflow has a real run history to baseline against.

## What this phase deliberately did NOT build

- **`deployment/` remains untouched and empty.** Per
  `ARCHITECTURE.md` Section 8, that directory is Phase 22's scope
  (actual deployment target/infrastructure). This phase prepares a
  publishable, versioned image (`release-image-publish.yml`) but stops
  there.
- **No full Postman/Newman regression run against the live compose
  stack.** `postman/README.md` (Phase 19) documents that this
  collection's auth flow requires manually copying a login response's
  token into the environment between requests — it has no pre-request/
  test script chaining tokens automatically. Automating that would mean
  modifying the Phase 19 collection, which is out of this phase's scope
  (`postman/` is Phase 19's component per the same Phase -> Component
  map). `docker-build.yml`'s smoke test instead verifies both services'
  own health endpoints, which is real, run-every-time verification of
  the same "does the compose stack actually come up together" question,
  short of full contract testing.
- **No hard-failing dependency-vulnerability gate.** Dependabot
  (`dependabot.yml`) and CodeQL (`codeql.yml`) both surface findings for
  a human to triage; nothing in this phase auto-fails a build purely on
  a dependency's version number. An OWASP `dependency-check-maven`-style
  hard gate was considered and deliberately not used — it now requires a
  provisioned NVD API key this project has never had reason to obtain,
  and would add a flaky external dependency to every backend CI run for
  a check CodeQL + Dependabot already cover between them.
- **No signed Flutter release build.** `flutter-ci.yml` builds an
  unsigned debug APK only, as noted in the table above.

## Recommended branch protection (not itself a file in this repo)

GitHub branch protection rules are a repository *setting*, not a file
this phase can commit — documented here as the recommended follow-up an
Admin/repo-owner should configure once these workflows have a green run
on `main`:

- Require `Backend CI`, `AI Service CI`, `Flutter CI`, and
  `Docker Build Validation` to pass before merging to `main`/`develop`.
- Require branches to be up to date before merging.
- Require the CodeQL check to have run (not necessarily to be
  zero-findings — see "CI failure handling" above) before merging.
