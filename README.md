# JANNet AI

An AI-assisted civic complaint management system. Citizens report civic issues
(potholes, garbage overflow, water leakage, broken street lights, open manholes,
illegal construction, etc.) via a mobile app; an AI service validates, deduplicates,
scores severity/priority, and estimates budget; the complaint is routed through a
department workflow from submission to resolution.

This repository is developed **phase-by-phase**. Each phase is scoped, implemented,
tested, and handed off independently — see `PROJECT_PROGRESS.md` and
`PHASE_HANDOFF.md` before starting any new work here.

## Tech Stack

| Layer | Technology |
|---|---|
| Frontend | Flutter + Dart |
| Main backend | Java + Spring Boot |
| AI service | Python + FastAPI |
| Database | MySQL (Flyway migrations) |
| Auth | Spring Security + JWT + RBAC |
| AI/CV | YOLOv11, OpenCV, OCR, Gemini API |
| Object storage | AWS S3 (or S3-compatible) |
| Containerization | Docker + Docker Compose |
| API testing | Postman |
| Cloud | AWS |

See `ARCHITECTURE.md` for the full system diagram and data flow.

## Repository Layout

```
jannet-ai/
├── backend/        # Spring Boot business backend (Team Member 1)
├── flutter/        # Flutter mobile app (Team Member 2)
├── ai-service/      # FastAPI AI microservice (Team Member 3)
├── database/        # MySQL schema, Flyway migrations, seed/test data (Team Member 4)
├── shared/          # Cross-cutting contracts: API/DTO specs, enums, error formats
├── docker/          # Dockerfiles + docker-compose definitions
├── postman/          # Postman collections/environments for API testing
├── deployment/       # AWS deployment configuration/scripts
├── docs/            # Additional documentation, ER diagrams, data dictionary
├── .github/workflows/ # CI/CD pipelines
├── README.md
├── PROJECT_PROGRESS.md     # Live state of the project
├── PROJECT_INTEGRATION.md  # Cross-module contracts and integration points
├── ARCHITECTURE.md         # System architecture and design decisions
├── PHASE_HANDOFF.md        # Latest phase-to-phase handoff record
├── .gitignore
└── .env.example
```

## Getting Started

1. Copy `.env.example` to `.env` and fill in real values (at minimum
   `MYSQL_ROOT_PASSWORD`, `DB_PASSWORD`, and `JWT_SECRET` — see that
   file's own header). Never commit `.env`.
2. `docker/` provides a `docker-compose.yml` that brings up MySQL, the
   backend, and the AI service together (added in Phase 18 — see
   `docker/README.md` for the full quick-start, prerequisites, and what
   is/isn't containerized):
   ```bash
   docker compose --env-file .env -f docker/docker-compose.yml up --build
   ```
3. Flutter runs on your host machine or an emulator/device, not inside a
   container — see `docker/README.md`'s "Connecting Flutter" section for
   the `--dart-define=API_BASE_URL=...` value to use.

## Team Ownership

| Member | Owns |
|---|---|
| 1 | Spring Boot backend, Spring Security/JWT/RBAC, REST APIs, business logic, complaint workflow, department assignment, notifications, backend↔AI integration, Docker, GitHub, CI/CD, AWS, monitoring, Postman |
| 2 | Flutter app: UI, navigation, state management, API integration, camera/gallery/GPS/maps, notifications, all role-based screens |
| 3 | Python AI service: FastAPI, YOLOv11, OpenCV, OCR, Gemini, image quality/duplicate detection, severity/priority/budget/resolution-time prediction, AI fallback, model versioning, AI tests |
| 4 | MySQL: schema, migrations, seed/test data, data dictionary, ER diagram, indexes, constraints, backup/restore |

Team Member 4 owns raw SQL/migration files; Team Member 1 owns the Java
JPA entities/repositories that map onto that schema — no ownership overlap.

## Development Process

This project follows a strict phase-isolated workflow: **one phase per Claude
Project/workspace**, fresh context each time, no silent scope creep into future
phases. Full rules are in the master development prompt supplied alongside the
SRS/BRD/FRS. In short:

- Only the current phase's tasks are implemented.
- Nothing is claimed as "working" or "tested" unless actually verified in this
  environment; unverifiable claims are marked `NOT VERIFIED`.
- Every phase ends with an updated `PROJECT_PROGRESS.md` and a new
  `PHASE_HANDOFF.md`, then stops — the next phase starts in a new workspace.

## Current Status

See `PROJECT_PROGRESS.md` for the authoritative, up-to-date status. As of this
commit: **Phase 18 — Docker Containerization** is complete.

PRE-EXISTING ISSUE FIXED WHILE ALREADY EDITING THIS FILE THIS PHASE: this
line had read "Phase 4" since early in the project's history and was
never updated by any intervening phase — each phase's own convention is
to update `PROJECT_PROGRESS.md`/`PHASE_HANDOFF.md`, not necessarily this
line of `README.md`, so it silently drifted. Corrected here since Phase
18 already needed to edit this file's Getting Started section anyway
(per this project's "fix opportunistically only when the phase already
requires touching that file" convention) — see
`PROJECT_INTEGRATION.md` Section 6.
# JenNet-AI
