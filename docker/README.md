# JanNet AI — Docker (Phase 18)

Containerizes the two locked-stack backend services (Spring Boot,
FastAPI AI service) plus a MySQL 8 database, orchestrated with Docker
Compose for local development and pilot deployment. Flutter is a mobile
client and is deliberately **not** containerized here — see "What isn't
containerized" below.

**NOT VERIFIED**: this project's workspace has never had a Docker daemon
or reachable container registry available (network is restricted to
language package registries only). Every file in this directory has been
validated by manual review only — YAML parsed with `yaml.safe_load`,
Dockerfile paths cross-checked against the actual repo layout, and env
var names cross-checked against `application.yml`/`app/config.py` — never
actually built or run. Treat this exactly like every other "NOT VERIFIED"
item this project has carried since Phase 3 (`mvn clean verify` against a
real MySQL instance): correct on paper, unverified in practice, and the
first thing a real Docker-capable environment should confirm.

## Prerequisites

- Docker Engine with Compose V2 (`docker compose`, not the standalone
  `docker-compose` v1 binary — this file uses the `condition:` form of
  `depends_on`, which v1 does not support).

## Quick start

Run every command from the **repo root**, not from inside `docker/` —
Compose's automatic `.env` file discovery looks in the current working
directory, and `docker/docker-compose.yml`'s two `build.context: ..`
entries are relative to the compose file's own location, not your shell's:

```bash
cp .env.example .env
# Edit .env: at minimum change MYSQL_ROOT_PASSWORD, DB_PASSWORD, and
# JWT_SECRET away from their placeholder values before doing anything
# beyond a disposable local sandbox run.

docker compose -f docker/docker-compose.yml up --build
```

This builds and starts three containers:

| Service | Image built from | Port (host:container) | Purpose |
|---|---|---|---|
| `mysql` | `mysql:8.0` (official) | `3306:3306` | Schema created by Flyway (`V1`–`V18`) on first backend startup — no manual `CREATE TABLE` step |
| `ai-service` | `docker/Dockerfile.ai-service` | `8001:8001` | FastAPI AI Analysis Module (SRS 20.3) |
| `backend` | `docker/Dockerfile.backend` | `8080:8080` (or `$SERVER_PORT`) | Spring Boot REST API |

`backend` waits for `mysql`'s healthcheck to pass before starting (Flyway
needs a live database connection immediately on boot); `ai-service` has
no such dependency and starts independently, since `AiClassificationService`
already tolerates `ai-service` being down or disabled at request time
(`AI_SERVICE_ENABLED=false` parks complaints for the Phase 6 manual
override — see `PROJECT_INTEGRATION.md` Section 1).

Check everything is healthy:

```bash
docker compose -f docker/docker-compose.yml ps
curl http://localhost:8080/actuator/health
curl http://localhost:8001/health
```

## Getting a first login

A fresh database has no users at all. Set `BOOTSTRAP_SUPER_ADMIN_MOBILE`/
`BOOTSTRAP_SUPER_ADMIN_PASSWORD`/`BOOTSTRAP_SUPER_ADMIN_NAME` in `.env`
*before* the first `up` — `SuperAdminBootstrap` (Phase 4) only ever runs
its no-op-if-empty check once, against whatever database state already
exists when the backend container starts.

## Connecting Flutter to a Compose-run backend

Flutter is not part of this Compose file (see below) and runs on your
host machine or an emulator/device, not inside a container. Point it at
whichever host address can actually reach the `backend` container's
published port:

```bash
# Desktop / web build, or a physical device on the same LAN with a real IP substituted:
flutter run --dart-define=API_BASE_URL=http://localhost:8080/api/v1

# Android emulator (10.0.2.2 is the emulator's alias for the host machine):
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080/api/v1
```

## Persisted data

Three named Docker volumes survive `docker compose down` (but not
`docker compose down -v`):

- `mysql_data` — the actual database files
- `backend_storage` — uploaded complaint photos (Phase 6's local-disk
  `StorageService` stub — see `PROJECT_INTEGRATION.md` Section 5)
- `ai_models` — mount point for a real YOLOv11 `.pt` file, so one can be
  dropped in without rebuilding the `ai-service` image (see
  `ai-service/models/README.md`). This volume shadows the image's
  baked-in `models/README.md` the first time it's created — a known,
  accepted trade-off, not a defect: copy the weight file in with
  `docker cp <file> jannet-ai-service:/app/models/` (or a
  `docker-compose.override.yml` bind mount) after first `up`.

## What isn't containerized, and why

- **Flutter** — a mobile client (Android/iOS), not a server process;
  there is nothing for a container to keep running. If a future phase
  adds Flutter *web* support, that would be a genuinely new deliverable
  (a static-file web server image) — not something this phase's
  component-map assignment (`docker/`) implies just because Flutter
  exists in the repo.
- **Real infrastructure/application monitoring** (SRS Section 20:
  CPU/memory/latency/error-rate/queue-depth on an "operations dashboard")
  — `ARCHITECTURE.md` Section 9 already flagged this as a Phase 18/Phase
  22 candidate before this phase started; this phase's own scope stayed
  containerization (build/run the four locked-stack components together)
  rather than also standing up a Prometheus/Grafana stack, which would be
  a new set of services and a new backend dependency
  (`micrometer-registry-prometheus`, not currently in `backend/pom.xml`)
  outside what "package what already exists into containers" implies.
  Left open for a future phase — see `PROJECT_INTEGRATION.md` Section 6.
- **CI/CD pipelines** — `.github/workflows/` is Phase 21's own component-
  map assignment, not this one's; a real pipeline would *use* these
  Dockerfiles (e.g. `docker build`/`docker push` steps) but building the
  pipeline itself is out of scope here.
- **AWS deployment configuration** — `deployment/` is Phase 22's own
  assignment. This phase's Compose file targets local/pilot use
  (`docker compose up` on a single host), not a production AWS topology
  (ECS/EKS task definitions, an RDS endpoint instead of the `mysql`
  container, etc.) — a future Phase 22 should treat these Dockerfiles as
  the images it deploys, not rebuild them from scratch.

## Rebuilding after a code change

```bash
docker compose -f docker/docker-compose.yml up --build backend
docker compose -f docker/docker-compose.yml up --build ai-service
```

## Tearing down

```bash
docker compose -f docker/docker-compose.yml down        # keeps volumes
docker compose -f docker/docker-compose.yml down -v      # also deletes them
```

## Flutter Web frontend and Android builds

- `flutter-frontend` - Flutter Web release build served by nginx, **always
  running** with the stack: http://localhost:3000.
- `flutter-android` - **on-demand** Flutter + Android SDK build environment
  (Compose profile `android-build`), producing APK/AAB into
  `flutter/build/app/outputs/` on the host.

A root `compose.yaml` includes this file, so `docker compose up -d` works from
the repository root. When using the long form `-f docker/docker-compose.yml`,
also pass `--env-file .env` (otherwise Compose reads `.env` from `docker/`).
Full guide: [`docs/FLUTTER_DOCKER_SETUP.md`](../docs/FLUTTER_DOCKER_SETUP.md).
