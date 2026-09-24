# JANNet AI — Architecture

Status: Established in Phase 1. Update this file whenever a later phase makes a
real architectural decision or deviates from what's documented here — do not let
it drift out of sync with the actual code.

## 1. System Overview

JANNet AI is a civic-issue reporting and resolution platform with four
architectural layers:

```
Flutter (mobile client)
   |
   | HTTPS REST  (/api/v1/...)
   v
Java Spring Boot  (main business backend)
   |
   +-------------> MySQL            (relational data of record)
   |
   +-------------> AWS S3           (complaint photos, resolution photos, documents)
   |
   +-------------> Python FastAPI AI Service  (/api/v1/ai/...)
                         |
                         +--> OpenCV                  (image preprocessing / quality checks)
                         +--> YOLOv11                 (civic-issue object detection)
                         +--> OCR                     (text extraction, e.g. plate/board numbers)
                         +--> Gemini API               (contextual reasoning / description generation)
                         +--> Duplicate Detection       (visual + geo + text similarity)
                         +--> Severity scoring
                         +--> Priority scoring
                         +--> Budget prediction
```

**Java Spring Boot is the single business backend.** It owns authentication,
authorization, the complaint workflow/state machine, department assignment,
notifications, and all persistence. It is the only service Flutter talks to
directly.

**Python FastAPI is exclusively an AI service.** It is stateless with respect
to business data — it receives an image/context, returns AI findings, and does
not itself own the complaint record, MySQL, or user-facing APIs. Spring Boot
calls it server-to-server and persists the results.

This is intentionally a **2-service architecture**, not a microservices mesh.
No message broker, cache layer, or orchestration platform is introduced unless
a real, demonstrated technical requirement forces it (see Section 7).

## 2. Component Responsibilities

### 2.1 Flutter (`flutter/`)
- Role-based UI: Citizen, Officer, Department Head, Admin, Super Admin.
- Camera/gallery capture, GPS tagging, map views.
- Consumes only the Spring Boot `/api/v1/...` contract — never calls the AI
  service or MySQL directly.
- Local state management only; no business logic that duplicates backend rules.
- Authentication Module fully integrated as of **Phase 17**
  (`features/auth/`): registration + OTP verification/resend, MFA
  verification (Admin/Super Admin), forgot/reset password, and
  server-side logout + "logout everywhere" all now have real screens
  calling the pre-existing `AuthController` contract — previously only a
  minimal login screen existed (Phase 6), and Admin/Super Admin accounts
  could not sign in through the app at all before this phase. See
  Section 9 for what's still open (Ward-at-registration, budget-approval
  visibility, Community Heatmap/Rate/Appeal).

### 2.2 Spring Boot Backend (`backend/`)
- Spring Security + JWT (access + refresh tokens) + RBAC.
- Complaint lifecycle state machine (Section 4).
- Department/officer assignment logic.
- Notification dispatch (push/SMS/email). **Implemented Phase 15**
  (in-app + real SMTP email + real HTTP-based SMS; push preference
  exists but is not yet actually dispatched - no device-token storage
  exists in this schema, see Section 9) - `service/notification/
  NotificationService` orchestrates dispatch/retry, triggered from
  `ComplaintService`/`DepartmentAssignmentService` (status-change/
  officer-assignment alerts) and `EscalationSchedulerService` (80%-SLA
  warnings).
- Government Dashboard + Analytics aggregation. **Implemented Phase 16**
  (`service/dashboard/AnalyticsAggregationService` computes KPI tiles,
  ward heatmap, category trend, department comparison, and Admin
  summary; `AnalyticsCacheService` refreshes these nightly and serves a
  cached, timestamped snapshot rather than recomputing on every request
  — see Section 9) - exposed via `GovernmentDashboardController`.
- Orchestrates calls to the AI service and persists AI results against the
  complaint record.
- Issues pre-signed URLs / proxies uploads to S3; MySQL stores only image
  metadata and object keys, never binary image data.
- All authorization decisions are enforced server-side. The Flutter client's
  role-based UI is a convenience, not a security boundary.
- **Containerized Phase 18:** `docker/Dockerfile.backend` — multi-stage
  Maven/JDK 21 build producing a slim JRE runtime image; see Section 8's
  Phase 18 row and `docker/README.md`.

### 2.3 AI Service (`ai-service/`)
- FastAPI app exposing `/api/v1/ai/...`.
- Image quality gate (reject unusable images before spending inference budget).
- YOLOv11 detection against the civic-issue class set (Section 5).
- OpenCV preprocessing, OCR where applicable.
- Gemini API calls for contextual/descriptive reasoning.
- Duplicate detection, severity, priority, and budget prediction — each a
  distinct, independently testable capability.
- Authenticated via a shared internal API key (`AI_SERVICE_API_KEY`); not
  publicly reachable from the internet in production.
- **Implemented Phase 7 (classification):** `POST /api/v1/ai/classify`
  — the image quality gate, YOLOv11 model-loading wrapper (no trained
  weights ship with this repo, see Section 5), Gemini cross-validation
  with fallback, OCR extraction, and confidence-scoring/routing logic are
  all real, running code. **Called by the Spring Boot backend as of
  Phase 8** (`backend/.../client/ai/AiServiceClient`, invoked from
  `AiClassificationService` right after complaint creation).
- **Implemented Phase 9 (duplicate detection):** `POST
  /api/v1/ai/duplicate-check` — perceptual-hash (dHash, not a learned
  embedding — no trained weights ship with this repo, same constraint as
  YOLOv11, see Section 5) image similarity, haversine geospatial
  proximity, and the SRS 15.6/21.5 tiering rule, all real, running code
  operating on a candidate pool the caller supplies (this service remains
  stateless with respect to business data — see below). **Called by the
  Spring Boot backend as of Phase 9** (`AiServiceClient.checkDuplicate`,
  invoked from `DuplicateDetectionService`, itself called by
  `AiClassificationService` right after a successful `/classify` call).
- **Implemented Phase 10 (priority + budget prediction):** `POST
  /api/v1/ai/priority-predict` (SRS 15.8/21.6) and `POST
  /api/v1/ai/budget-predict` (SRS 15.9/21.8) — a static rules-based
  severity/priority scoring table and a static rules-based cost/
  resolution-time estimation table, both real, running code; neither is a
  trained ML model, since no historical resolution or cost dataset ships
  with this repo (same constraint as YOLOv11's untrained weights and
  duplicate-detection's perceptual hash, see Section 5). **Called by the
  Spring Boot backend as of Phase 10**
  (`AiServiceClient.predictPriority`/`predictBudget`, invoked from the new
  `PriorityBudgetPredictionService`, itself called from both
  `AiClassificationService.applyAutoVerification` and
  `ComplaintService.verify`'s `VERIFIED` branch — see Section 4). All four
  `/api/v1/ai/*` endpoints in SRS 20.3's contract table are now
  implemented and consumed; ai-service's public API surface for this
  project is complete as of this phase. See `PROJECT_INTEGRATION.md`
  Sections 1 and 2, and this file's Section 4 and Section 9 for full
  wiring detail.
- **Statelessness, reaffirmed Phase 9:** the duplicate-detection endpoint
  needed a candidate pool to compare against ("existing open complaints
  in the same ward," SRS 15.6) but this service still does not query
  MySQL, own the complaint record, or look anything up itself — the
  candidate pool travels in the request body, supplied by the Spring
  Boot backend, which does own that data. This is the locked
  architecture applied to a harder case, not an exception to it — see
  `PROJECT_INTEGRATION.md` Section 6 for the full decision record.
- **Containerized Phase 18:** `docker/Dockerfile.ai-service` — Python
  3.11-slim plus the system packages OpenCV/ultralytics/pytesseract need
  at import/runtime (`tesseract-ocr`, `libgl1`, `libglib2.0-0`) that a
  minimal base image doesn't ship by default; see Section 8's Phase 18
  row and `docker/README.md`.

### 2.4 Database (`database/`)
- MySQL, schema-versioned with Flyway (`V1__...sql`, `V2__...sql`, ...).
- No large binary/image data in MySQL — S3 is the source of truth for media;
  MySQL stores references.
- Owned by Team Member 4; JPA entity mapping owned by Team Member 1 against
  the same schema (no duplicate schema definitions).

### 2.5 Shared (`shared/`)
- Cross-cutting API/DTO contracts, shared enums (complaint status, roles,
  issue categories), and the common error-response format, so Java, Python,
  and Flutter stay in sync without copy-pasted, drifting definitions.

## 3. API Contract Convention

- Business backend: `/api/v1/...`
- AI service: `/api/v1/ai/...`
- All backend APIs documented via OpenAPI/Swagger.
- Request/response fields, enums, status codes, auth, and error format are
  kept synchronized across Java (producer/consumer of AI contract), Flutter
  (consumer of Java contract), and Python (producer of AI contract). Changes
  to any contract are recorded in `PROJECT_INTEGRATION.md`.

## 4. Complaint Workflow (state machine)

```
Draft (client-side only — never persisted server-side, see below)
  -> Submitted
       -> AI Processing
            -> Verified
                 -> Assigned
                      -> In Progress
                           -> Resolved
                                -> Closed

Alternate/side states reachable at the appropriate points:
  -> Duplicate       (from AI Processing, via AI or the Phase 6 manual override)
  -> Rejected        (from AI Processing / Verified / Assigned — never once In Progress)
  -> Reopened         (Resolved/Closed -> In Progress, citizen-initiated, grace-period-gated)

Escalated and Reopened are ANNOTATIONS on the current status
(is_escalated/is_reopened + timestamp columns), not status values a
complaint's `status` column is ever actually set to — decided in Phase 6,
see PROJECT_INTEGRATION.md Section 6. The underlying status keeps
progressing normally once flagged.
```

Rules:
- This is a strict state machine — arbitrary transitions are not permitted.
  Valid transitions are enforced server-side in Spring Boot.
- Every status transition is recorded (who/when/from/to/reason) for audit and
  SLA tracking — `status_history` (V10), append-only.
- **The exact transition table (allowed source→target pairs per role) was
  finalized and implemented in Phase 6** (`ComplaintStateMachine`) — the
  shape above is no longer just a placeholder; it's what's actually
  enforced. **Phase 11 update:** `Verified -> Assigned` is no longer
  theoretical — `DepartmentAssignmentService` now performs this
  transition automatically the moment a complaint reaches `Verified`
  (both the auto-verify and manual-verify paths), so `Assigned` and
  everything downstream of it (`In Progress`, `Resolved`, `Closed`) are
  real, exercisable transitions for the first time. `Draft` is never
  persisted at all — Flutter never has a reason to call the create API
  before the photo is ready to send, so there's no server-observable
  Draft state to model (see `ComplaintService.create`'s Javadoc).
- **Phase 6's approved stand-in for the not-yet-built AI Analysis
  Module:** a manual Verification Team override
  (`PATCH /api/v1/complaints/{id}/verify`, restricted to
  VERIFICATION_TEAM/ADMIN/SUPER_ADMIN) makes the same
  Verified/Rejected/Duplicate call a real AI confidence check would.
  Every complaint is created with `category=GENERAL` and parks at
  `AI Processing`. **As of Phase 8, this is no longer the only path**:
  `AiClassificationService` calls `ai-service` synchronously, right after
  creation, and — when the response's `requires_manual_review` is
  `false` — auto-transitions the complaint straight to `Verified` with a
  real category, before the citizen's create request even returns. **As
  of Phase 9, duplicate-checking runs too**, right after a successful
  classify call: a confirmed duplicate (≥80% image similarity within
  50m/30 days of an existing open complaint in the same ward) takes
  priority over classify's own verdict and auto-transitions the complaint
  straight to `Duplicate` instead, linked to its parent, with the
  parent's `corroboration_count` incremented — the same effect the manual
  override's own Duplicate decision already had, just system-initiated. A
  borderline (60-80%) possible duplicate also overrides classify's
  verdict, but in the other direction — it parks the complaint at `AI
  Processing` for a human either way, regardless of classification
  confidence. Only when duplicate-checking finds nothing does classify's
  `requires_manual_review` decide auto-verify vs. manual review, as
  before. Whenever ai-service is unreachable/disabled/erroring for either
  call, or a complaint's ward can't be resolved (so no duplicate check
  can even be attempted), the complaint parks at `AI Processing` exactly
  as before, and the manual override remains the *only* path out — it is
  the fallback/QA path Phase 7's handoff note said it would become, not
  removed. **As of Phase 10, the moment a complaint reaches `Verified`**
  (through either the automated path above or the manual override's own
  Verified decision), **severity/priority/budget prediction runs** —
  `PriorityBudgetPredictionService` calls ai-service's `/priority-predict`
  then `/budget-predict`, sets `complaints.severity` (unless the manual
  override already supplied one, which always takes precedence),
  populates the existing `predictions` row's `predicted_severity`/
  `priority_score`, and inserts a `budget` row (always
  `confidence_level='PRELIMINARY'` this phase — see Section 5). This does
  not run for a complaint that lands at `Duplicate` or stays parked at `AI
  Processing` for manual review — neither ever reaches `Verified`. See
  `PROJECT_INTEGRATION.md` Section 6 for the full Phase 8, Phase 9, and
  Phase 10 decision records.

## 5. AI Model Notes

- Expected YOLOv11 detection classes (subject to refinement once real
  training data is available): `pothole`, `garbage_overflow`, `water_leakage`,
  `broken_street_light`, `open_manhole`, `illegal_construction`.
- **No trained model exists yet.** Phase 7 (AI Service) will build the real
  model-loading architecture, dataset structure, training/eval/inference
  configuration, and versioning/placement conventions — but will not fabricate
  a trained model, an accuracy number, or a live prediction. Anything
  model-dependent is explicitly marked unavailable until a real model is
  trained and placed per that phase's documented instructions.
- **Fulfilled Phase 7 (loading architecture only, still no trained
  model):** `app/services/yolo_service.py` is real, functional
  Ultralytics-based loading/inference code — it will correctly load and
  run against a genuine `.pt` weights file the moment one exists — but
  since none ships with this repo, `is_available()` is `false` in every
  environment and `/api/v1/ai/classify` honestly reports
  `model_available: false` rather than inventing a detection. Dataset
  collection/labeling conventions and a training/eval harness remain
  explicitly out of scope (not this phase's deliverable — see
  `ai-service/models/README.md`, "Dataset / training / eval — explicitly
  out of scope this phase"). Placement/versioning convention: same file.
- **Implemented Phase 10 (priority/budget "model," deliberately not ML):**
  neither `priority_service.py` nor `budget_service.py` is a trained
  model or a statistical fit to real data — both are static rules tables
  (SRS 15.8/15.9's own documented fallback behavior for insufficient
  historical data, which describes every request this project has ever
  made, since no historical resolution/cost dataset has ever existed
  here). This is the same honest-about-what-exists choice as YOLOv11's
  untrained weights above and Phase 9's perceptual hash instead of a
  learned embedding — not a placeholder standing in for something
  smarter, but the complete, currently-correct implementation of the
  SRS's own fallback logic. See `PROJECT_INTEGRATION.md` Section 6 for
  the full decision record.

## 6. Security Baseline

Enforced across the backend (fully implemented starting Phase 4):
Spring Security, JWT + refresh tokens, RBAC, password hashing, OTP,
MFA-ready design, rate limiting, input validation, file validation, secure
file access (signed URLs, not public buckets), audit logs, server-side
authorization on every endpoint, HTTPS-ready config, CORS policy, secure
headers. No secret values live in source control — see `.env.example`.
Extended into CI (Phase 21): `.github/workflows/docker-build.yml`'s
compose smoke test generates its own ephemeral, run-scoped credentials
with `openssl rand` rather than using any repository secret or
hard-coded value; `.github/workflows/codeql.yml` (SAST) and
`.github/dependabot.yml` (dependency-version alerts) give this baseline
an automated, ongoing check rather than only a point-in-time one. See
`.github/workflows/README.md` for the full CI secret/environment-handling
account. Extended into production infrastructure (Phase 22):
`deployment/aws/terraform/` — RDS master password managed entirely by
AWS Secrets Manager (never a Terraform variable/tfvars value), RDS
`require_secure_transport = ON` plus a matching prod-profile-only JDBC
TLS override in `application-prod.yml`, EC2 secrets fetched from SSM
Parameter Store via an IAM instance role scoped to this project's own
parameter path only (never a wildcard), security groups restricting SSH
to an explicit admin CIDR and the database to the app host alone, and a
GitHub OIDC federated role so CI-driven deploys never need a static AWS
access key. See `deployment/README.md` and `deployment/ssm/PARAMETERS.md`
for the full account.

## 7. Explicit Non-Goals (avoid overengineering)

This is a 4-person academic-scale project. The architecture deliberately
excludes Kafka, RabbitMQ, Redis, Kubernetes, and additional microservices
beyond the two described above, unless a real, demonstrated requirement or
technical blocker is documented here first. Default to the simplest design
that satisfies the SRS.

## 8. Phase → Component Map

| Phase | Primary component(s) touched |
|---|---|
| 1 | Repo foundation, this document |
| 2 | `database/` |
| 3 | `backend/` (Spring Boot skeleton) |
| 4 | `backend/` (auth, JWT, RBAC) |
| 5 | `backend/` (citizen module - profile management, ward lookup). Flutter deferred by explicit instruction this phase; fulfilled in Phase 6 (see PHASE_HANDOFF.md). |
| 6 | `backend/`, `flutter/` (complaint module, state machine impl. — DONE. `flutter/` scaffolded from empty this phase: submission + tracking screens, plus a minimal login screen — see PHASE_HANDOFF.md and PROJECT_INTEGRATION.md Section 6.) |
| 7 | `ai-service/` (AI Analysis Module — classification only. `POST /api/v1/ai/classify` — DONE. Duplicate detection and severity/priority/budget prediction deliberately NOT built (Phases 9/10). Not yet called by `backend/` this phase — wired in Phase 8, see PROJECT_INTEGRATION.md Section 1 and Phase 6's manual-override note in Section 4.) |
| 8 | `backend/` ↔ `ai-service/` integration — DONE. `client/ai/` (HTTP client) + `AiClassificationService` (orchestration) call `POST /api/v1/ai/classify` right after complaint creation, persist a `Prediction` row, and apply the SRS 15.4 routing rule (auto-verify vs. leave parked for the Phase 6 manual override). See PROJECT_INTEGRATION.md Sections 1, 2, 6 and Section 9 below (open item resolved). |
| 9 | `ai-service/` (duplicate detection — `POST /api/v1/ai/duplicate-check`, perceptual-hash/geo/time-window tiering — DONE) + `backend/` (`AiServiceClient.checkDuplicate`, new `DuplicateDetectionService`, `AiClassificationService` rewritten to sequence classify then duplicate-check into one combined routing decision — DONE). See PROJECT_INTEGRATION.md Sections 1, 2, 6 and Section 9 below. |
| 10 | `ai-service/` (severity/priority/budget prediction — `POST /api/v1/ai/priority-predict`, `POST /api/v1/ai/budget-predict`, static rules-based scoring/estimation — DONE) + `backend/` (`AiServiceClient.predictPriority`/`predictBudget`, new `PriorityBudgetPredictionService`, wired into the `VERIFIED` transition from both `AiClassificationService.applyAutoVerification` and `ComplaintService.verify` — DONE). All four `/api/v1/ai/*` endpoints in SRS 20.3's contract are now implemented and consumed. See PROJECT_INTEGRATION.md Sections 1, 2, 6 and Section 9 below. |
| 11 | `backend/` (department assignment — routing-rule-based category->department resolution with a "General Triage" fallback, officer load-balancing by open-complaint count, a new minimal Admin routing-rule create/list endpoint, manual reassignment, a real `@Scheduled` SLA-breach escalation sweep, and the SRS 15.9 budget-approval-threshold gate — DONE). Wired into the `VERIFIED` transition from both `AiClassificationService.applyAutoVerification` and `ComplaintService.verify`, immediately after Phase 10's prediction step. See PROJECT_INTEGRATION.md Sections 1, 2, 6 and PROJECT_PROGRESS.md for full detail. |
| 12 | `backend/` (Officer Module actions: role-scoped queue on `GET /complaints`, `PATCH .../status` converted to multipart with `after_photo` support, new `PATCH .../classification`, `PATCH .../escalate`, `POST .../notes` — DONE) + `flutter/` (Officer Queue screen, Officer Complaint Detail screen with status-update/classification-override/escalate/internal-notes actions, role-based post-login routing — DONE). See PROJECT_INTEGRATION.md Section 6 and PROJECT_PROGRESS.md for full detail. |
| 13 | `backend/` (Department Head Module: new `DepartmentPerformanceService` + `GET /departments/{id}/officers`\|`/performance`\|`/performance/export`, all server-side scoped so a DEPARTMENT_HEAD sees only their own department; plus a security fix closing a pre-existing gap in `ComplaintService.reassign`/`.approveBudget`, which had NO department-scope check since Phase 11 — DONE) + `flutter/` (Department Performance View — KPI tiles, SLA-compliance bar, officer workload table, CSV export via clipboard-copy; Reassign Officer dialog wired to Phase 11's `PATCH .../assign`; new `DepartmentHeadHomeScreen` (Queue + Performance tabs); also fixed a pre-existing broken `session.dart` import in `home_router.dart` — DONE). See PROJECT_INTEGRATION.md Section 6 and PROJECT_PROGRESS.md for full detail. |
| 14 | `backend/` (Admin & Settings Module: new `AdminUserController`\\|`AdminSettingsController`\\|`AdminAuditLogController`, plus `AdminRoutingRuleController`'s new `/history`\\|`/{id}/deactivate` endpoints; new `PlatformSettingsService`/`PlatformSettingKey` fronting `RoutingRuleService`/`EscalationSchedulerService`/`ComplaintService`'s existing `@Value` thresholds with an optional Admin-set PLATFORM `settings` override — DONE) + `flutter/` (new `AdminHomeScreen` — Users/Settings/Routing Rules/Audit Log tabs; staff account creation with a one-time temporary password, role/status management, password-reset trigger, session revocation; Admin Settings screen; Routing Rules screen with history + deactivate; read-only Audit Log screen — DONE). See PROJECT_INTEGRATION.md Section 6 and PROJECT_PROGRESS.md for full detail. |
| 15 | `backend/` (Notification Module: new `NotificationController` — `GET /notifications`, `GET`\\|`PUT /notifications/preferences`; new `service/notification/` package — `NotificationService` orchestrating dispatch/retry, `EmailGatewayClient`/`SmsGatewayClient` real gateway implementations, `NotificationOtpDeliveryService` replacing the Phase 4 `LoggingOtpDeliveryService` stub; wired into `ComplaintService.recordHistory`/`.reassign`, `DepartmentAssignmentService.assignAndApply`, and a new `EscalationSchedulerService.sweepForSlaWarnings` 80%-SLA sweep — DONE) + `backend/` (Personal Settings: new `service/settings/PersonalSettingsService`/`PersonalSettingKey`, `UserController`'s new `GET`\\|`PATCH /users/me/settings` — DONE) + `flutter/` (new `features/notifications/` — Notifications list screen, preferences; new `features/settings/` — Personal Settings screen with language/accessibility/officer-availability/notification-preference controls; notification-bell and settings-gear entry points added to all four existing home shells — DONE). PUSH channel dispatch deliberately NOT implemented (no device-token storage exists in this schema — documented limitation, not a gap overlooked). See PROJECT_INTEGRATION.md Section 6 and PROJECT_PROGRESS.md for full detail. |
| 16 | `backend/` (Government Dashboard Module + Analytics Module: new `dto/dashboard/` (7 DTOs) + `GovernmentDashboardController` — `GET /dashboard/overview`\\|`/department-comparison`\\|`/admin-summary`\\|`/export`; new `service/dashboard/` — `AnalyticsAggregationService` (stateless KPI/heatmap/category-trend/department-comparison/admin-summary computation), `AnalyticsCacheService` (nightly `@Scheduled` refresh + startup priming + degraded-fallback caching, one snapshot per department plus a jurisdiction-wide snapshot), `GovernmentDashboardService` (role-scoping + CSV export); repository additions to `ComplaintRepository`/`StatusHistoryRepository`/`UserRepository`/`AuditLogRepository`/`NotificationRepository` — DONE) + `flutter/` (new `features/dashboard/` — `GovernmentDashboardScreen` with KPI tiles/SLA-compliance bar/category-trend bars/ward-heatmap bars, plus department-comparison and full Admin-summary sections when jurisdiction-wide; wired as a third tab into `DepartmentHeadHomeScreen` (own department) and a fifth, first-position tab into `AdminHomeScreen` (jurisdiction-wide) — DONE). Officer Dashboard (SRS 24.2) and Citizen Dashboard (SRS 24.1) deliberately NOT built this phase (out of scope per this phase's own title); full Reports Module (SRS 15.12: PDF, scheduled email) deliberately NOT built (CSV-only export instead, following Phase 13's precedent) — documented limitations, not gaps overlooked. See PROJECT_INTEGRATION.md Section 6 and PROJECT_PROGRESS.md for full detail. |
| 17 | `flutter/` only (full integration — DONE). Scope was not pre-recorded and had to be determined by auditing every backend endpoint against every endpoint Flutter called (see PROJECT_PROGRESS.md's "SCOPE DETERMINATION"); resolved as completing Flutter's integration of the Authentication Module (SRS 15.2/18), which had existed complete and unmodified in the backend since early phases but was deliberately left at Phase 6's bare-minimum login-only client. New: `features/auth/screens/register_screen.dart`, `otp_verification_screen.dart`, `mfa_verification_screen.dart`, `forgot_password_screen.dart`, `reset_password_screen.dart`. Modified: `auth_api.dart` (added `register`/`verifyOtp`/`resendOtp`/`verifyMfa`/`forgotPassword`/`resetPassword`/`logoutAll`; `login()` now returns a `LoginResult` instead of throwing on MFA; `logout()` now also revokes the session server-side, best-effort), `login_screen.dart` (Register/Forgot-Password links, MFA routing), `personal_settings_screen.dart` (new "Account" section, "Log out of all devices"). Zero backend/database/ai-service changes. Three items found by this phase's audit were deliberately left out of scope: Ward dropdown at registration (`GET /wards` requires auth a pre-registration citizen can't have), Community Heatmap/Rate Resolution/Appeal Rejection (no backend endpoint exists for any of the three), and the budget-approval action (`ComplaintResponse` has no budget fields to show). See PROJECT_INTEGRATION.md Section 6 and PROJECT_PROGRESS.md for full detail. |
| 18 | `docker/` (containerization — DONE). New: `docker/Dockerfile.backend` (multi-stage Maven/JDK 21 build, non-root runtime, `/actuator/health` healthcheck), `docker/Dockerfile.ai-service` (Python 3.11-slim + tesseract-ocr/libgl1 system deps, non-root runtime, `/health` healthcheck), `docker/docker-compose.yml` (mysql 8.0 + ai-service + backend, named volumes for MySQL data/complaint-photo storage/YOLOv11 model weights, `backend` gated on `mysql`'s healthcheck), `docker/README.md`. Also closed a pre-existing gap found during this phase's own audit: root `.env.example` and root `.gitignore` are referenced by name throughout the codebase (`application.yml`, `api_config.dart`, this file's own README, several Phase 4/8 `PROJECT_INTEGRATION.md` entries) but did not exist in the Phase 1–17 deliverable ZIPs at all — both recreated from the current authoritative config sources, not from unverifiable prior history. Zero backend/ai-service/database/flutter source changes. Flutter deliberately not containerized (mobile client, not a server process — see `docker/README.md`'s "What isn't containerized" section); real infrastructure/application monitoring (SRS Section 20) and CI/CD (`.github/workflows/`) also deliberately left for their own future phases. See `PROJECT_INTEGRATION.md` Section 6 and `PROJECT_PROGRESS.md` for full detail. |
| 19 | `postman/` (API documentation & Postman collection — DONE). New: `postman/JanNet_AI.postman_collection.json` (Postman Collection Schema v2.1.0; 56 requests across 12 folders — Authentication, Users, Wards, Complaints, Departments, Government Dashboard & Analytics, Notifications, Admin Users, Admin Routing Rules, Admin Settings, Admin Audit Log, and AI Service (Internal)), `postman/JanNet_AI_Local.postman_environment.json` (paired environment: `baseUrl`/`aiServiceBaseUrl` default to the same `localhost:8080`/`localhost:8001` ports `docker/docker-compose.yml` publishes, plus every path/id variable the collection's requests reference), `postman/README.md` (import/workflow instructions, error-envelope/pagination reference, validation methodology). Every one of the 56 requests corresponds to a real, currently-implemented endpoint — cross-checked one-for-one against the live `@GetMapping`/`@PostMapping`/`@PutMapping`/`@PatchMapping` annotations in all eleven backend `controller/*.java` files and all four `ai-service/app/api/routes/*.py` route modules (not copied from this document's own prose, which was used only as a cross-reference); zero speculative/future-phase endpoints included. Zero backend/ai-service/database/flutter source changes. See `PROJECT_INTEGRATION.md` Section 6 and `PROJECT_PROGRESS.md` for full detail, including the validation methodology and what was deliberately left out (Actuator/Swagger endpoints, a Compose-internal-hostname environment variant, and any endpoint that doesn't exist yet). |
| 20 | all — testing (DONE). New: `backend/src/test/java/...` (10 new JUnit5/Mockito/MockMvc/`@DataJpaTest` test classes covering the complaint state machine, `ComplaintService`'s Phase 13 department-scope security fix and reopen-grace-period logic, `AuthService`'s lockout/MFA rules, `JwtService`, `JwtAuthenticationFilter`, `NotificationService`'s retry-with-backoff, `DepartmentAssignmentService`'s routing/load-balancing, one `@WebMvcTest` controller-slice test, and one `@DataJpaTest` repository test against a real Flyway-migrated H2 schema), `backend/pom.xml` (new `h2` test-scope dependency), `backend/src/test/resources/application-test.yml` (new `test` Spring profile); `ai-service/tests/` (4 new pytest files — API-contract/auth/image-fetch/yolo-service tests — plus one test-fixture bug fixed in the pre-existing `test_preprocessing.py`; this phase's own environment re-check found pypi.org reachable for the first time, so these 74 tests were **actually executed**, unlike the rest of this phase); `flutter/test/` (4 new widget/unit test files — `ComplaintStatus` enum, `Complaint`-family JSON models, `StatusBadge` widget, `LoginScreen` widget); `database/validation/validate_migrations.py` (new, actually executed — static Flyway migration validation: version-sequence, FK dependency-ordering, SQL syntax, entity/schema drift). Zero backend/ai-service/database/flutter *application* source changed — testing only, per this phase's own scope. See `PROJECT_INTEGRATION.md` Section 6 and `PROJECT_PROGRESS.md` for full detail, including exactly which backend/Flutter tests were executable versus NOT VERIFIED (no Maven Central / no Flutter SDK in this workspace) and the one genuine test-fixture bug this phase's real pytest execution found and fixed. |
| 21 | `.github/workflows/` (CI/CD automation — DONE). New: `backend-ci.yml` (JDK 21 `mvn clean verify` — full Phase 20 JUnit suite against H2-in-MySQL-mode, no external MySQL service needed, `backend.jar` packaging, Surefire + JaCoCo artifacts), `ai-service-ci.yml` (Python 3.11 pytest with coverage; no `.env`/secrets needed — every `Settings` default is already safe), `flutter-ci.yml` (`pub get`/`analyze`/`test --coverage`/`build apk --debug`), `docker-build.yml` (builds both Phase 18 Dockerfiles for the first time ever, Trivy image scan (report-only), then a `docker compose up` smoke test with ephemeral `openssl rand`-generated credentials verifying both services' health endpoints), `codeql.yml` (CodeQL SAST for Java + Python), `release-image-publish.yml` (builds/publishes both images to GHCR on a `v*.*.*` tag only — publishes, does NOT deploy), `.github/dependabot.yml` (weekly Maven/pip/pub/Docker/Actions dependency-update PRs), `.github/workflows/README.md` (full documentation). Two small, documented build-tooling-only additions needed to support the above: `backend/pom.xml` gained a test-phase-bound `jacoco-maven-plugin` (coverage report generation, no gate enforced), `ai-service/requirements.txt` gained test-scope `pytest-cov`; no application/test source changed in either file. Zero backend `main/`/`test/`, ai-service `app/`/`tests/`, `database/migrations/`, flutter `lib/`/`test/`, or `docker/` (Dockerfiles/compose file) source changed. `postman/` and `deployment/` both untouched — full Newman regression automation deliberately deferred (Phase 19's collection has no token-chaining test scripts to automate; adding them would be Phase 19-scope surgery) and `deployment/` remains Phase 22's reserved territory. See `.github/workflows/README.md`, `PROJECT_INTEGRATION.md` Section 6, and `PROJECT_PROGRESS.md` for full detail, including which parts of this phase were actually validated (YAML syntax, real Action-tag existence, real input-name cross-checks against fetched `action.yml` files, `.env.example` variable-name cross-checks) versus NOT VERIFIED (no live GitHub Actions runner in this workspace to execute a workflow end-to-end). |
| 22 | `deployment/` (Production Deployment on AWS — DONE). New: `deployment/aws/terraform/` (VPC/subnets with no NAT Gateway, security groups, RDS MySQL 8.0 with AWS-managed master password and a TLS-required parameter group, EC2 `t3.small` app host with an IAM instance role, CloudWatch alarms + SNS, optional Route53 record, GitHub OIDC deploy role — no static AWS credential anywhere), `deployment/scripts/ec2-user-data.sh.tpl` (first-boot bootstrap: Docker/Compose/AWS-CLI install, SSM/Secrets-Manager secret fetch, `.env` generation, GHCR image pull, `docker compose up`, nginx+certbot), `deployment/docker-compose.prod-override.yml` (swaps Phase 18's build-from-source services for the Phase-21-published GHCR images; drops the local `mysql` container for RDS), `deployment/nginx/` (TLS 1.2+/1.3 reverse proxy, `ai-service` never exposed publicly), `deployment/ssm/PARAMETERS.md`, `deployment/scripts/deploy.sh`\\|`rollback.sh`\\|`health-check.sh` (SSM Run Command based — no SSH key needed by CI), `.github/workflows/deploy-aws.yml` (new, additive to Phase 21's `.github/workflows/` — reacts to the same `v*.*.*` tag `release-image-publish.yml` reacts to, deploys via OIDC, never rebuilds/republishes), `deployment/flutter/build_release.sh` (production build using the existing `--dart-define=API_BASE_URL` mechanism — no Flutter source change). One small, documented, additive production-config change needed *for* the above: `backend/src/main/resources/application-prod.yml` gained a prod-profile-only JDBC URL requiring TLS to MySQL, since the new RDS parameter group enforces `require_secure_transport = ON` — base `application.yml` (dev/test/local) completely unchanged. Zero live AWS execution was possible in this sandbox (no account/credentials/network reach) — every artifact is complete and staticly validated (HCL/YAML/shell syntax, cross-file variable-name consistency) but not run; see `deployment/VERIFICATION.md` for the full, itemized breakdown. Explicit open items, not silently claimed as done: no S3 object-storage backend (still `LocalStorageService` on an EBS volume), no application-level CloudWatch alerts (only infrastructure-level), no automated EBS snapshots, no signed Flutter release, RDS TLS encrypted but not certificate-chain-verified. See `PROJECT_INTEGRATION.md` Section 6 and `PROJECT_PROGRESS.md` for full detail. |
| 23 | all — final integration testing (DONE — **FINAL PHASE, no Phase 24**). Verification and genuine-defect-fixing only, per this phase's own "do not invent missing features" instruction. The only application source touched anywhere in the project: `database/migrations/V6__create_complaints.sql` (one `CHECK` constraint removed — MySQL 8/InnoDB rejects a column that carries a foreign key's referential action from also participating in a `CHECK`, found by this phase's own first-ever real execution of the Flyway migrations against a real MySQL 8.0.46 server; the same rule remains enforced at the application layer in `ComplaintService`). Also removed: a stray, empty `deployment/{aws` directory left over from Phase 22's own packaging (a shell brace-expansion artifact). Real execution performed for the first time in this project's history: all 18 Flyway migrations against real MySQL 8.0.46 (found and fixed the defect above; all 15 tables created after the fix); the ai-service pytest suite (74/74, reconfirming Phase 20's result at the Phase 22 baseline); `python3 -m py_compile` on all ai-service source; a real, failed `mvn compile` attempt confirming Maven Central remains unreachable rather than assumed. Static validation reconfirmed at the Phase 22 baseline: 56-endpoint 1:1 backend+AI-vs-Postman cross-check, `SecurityConfig`'s full authorization chain, brace/paren balance on all 195 Java + 55 Dart + 10 Terraform files, `yaml.safe_load` on all CI/compose YAML, `json.load` on both Postman files. NOT VERIFIED, unchanged from prior phases: a full backend compile/test run (Maven Central unreachable), Flutter `pub`/`analyze`/`test`/`build` (no Flutter SDK reachable), `terraform validate`/`plan`/`apply` (no HashiCorp registry reachable), a real Docker daemon build, any live AWS/GitHub-Actions/Newman execution. Diff audit against the untouched Phase 22 baseline: exactly two differences project-wide (the one migration fix, the one stray-directory deletion) — the smallest, most precise diff of any phase in this project. See `PROJECT_INTEGRATION.md` Section 6, `PHASE_HANDOFF.md`, and `PROJECT_PROGRESS.md` for full detail, including the complete 21-point verification-checklist breakdown and every explicitly-carried-forward known limitation (S3 storage, application-level CloudWatch alerts, push notification delivery, Officer/Citizen Dashboards, the full Reports Module, several Phase-17 SRS-alignment items, RDS certificate pinning, a signed Flutter release build) that this final phase deliberately did not attempt to close, since none of them are genuine integration defects and none were in this phase's verification-only scope. |

## 9. Open Architectural Questions

### Phase 23 — MySQL 8 rejects a CHECK constraint on a self-referencing FK's column — RESOLVED this phase (first real-execution finding of the whole project)

Every migration in `database/migrations/` had been validated statically
only (manual SQL review, `validate_migrations.py`'s version-sequence/
FK-ordering/schema-drift checks) for 22 phases, because no real MySQL
server was reachable in-sandbox until this phase. Running all 18
migrations for real against a freshly-installed MySQL 8.0.46 surfaced a
genuine defect on the very first attempt: `V6__create_complaints.sql`'s
`chk_complaints_not_self_parent` CHECK constraint referenced
`parent_complaint_id`, the same column carrying
`fk_complaints_parent_complaint`'s self-referencing `ON DELETE SET
NULL` — InnoDB rejects that combination outright (MySQL error 3823) as
a platform-level restriction, not a modeling mistake specific to this
schema.

**Resolution:** removed the CHECK constraint; the identical rule is
already enforced in `ComplaintService`'s DUPLICATE-decision branch
(rejects `parentComplaintId.equals(complaintId)` before any write), so
nothing is actually less safe — only the DB-level defense-in-depth copy
of an already-enforced rule is gone. This is the concrete case this
document's own "Key learnings" (static validation is insufficient for
schema correctness) was written to anticipate, closing this project's
single longest-standing carried-forward gap. Full rationale:
`PROJECT_INTEGRATION.md` Section 6, "Phase 23 — Real MySQL 8 execution
surfaced a genuine InnoDB defect..."; inline comment at the exact site
of the removed constraint in `V6__create_complaints.sql` itself.

### Phase 17 — Ward dropdown at registration (SRS Screen 16.1) — Explicitly left open

`GET /api/v1/wards` is `authenticated()` in `SecurityConfig` (Phase 5), so
`RegisterScreen` cannot call it — a not-yet-registered citizen has no
JWT. Resolved this phase by submitting `wardId: null` at registration
(`RegisterRequest.wardId` is already nullable for exactly this reason).
No screen anywhere in this app lets a citizen set their ward after
registration either. Left open for a future phase to resolve explicitly
between two options: (a) a public, unauthenticated ward-lookup endpoint
variant, or (b) a ward field added to Personal Settings so it's set
post-registration instead of at signup. See `PROJECT_INTEGRATION.md`
Section 6 ("Phase 17 — Ward dropdown at registration...") for the full
reasoning.

### Phase 17 — Budget-approval visibility needs a `ComplaintResponse` change before any Flutter screen can use it — Explicitly left open

`PATCH /complaints/{id}/approve-budget` (Phase 11) is real and working,
but `ComplaintResponse` carries no budget amount, threshold, or
approval-status field at all, so this phase left it unwired rather than
build a button with nothing for staff to see. A future phase should add
the relevant fields to `ComplaintResponse` (or a small nested
`BudgetSummaryResponse`) as its own first backend step before attempting
the Flutter side. See `PROJECT_INTEGRATION.md` Section 6 ("Phase 17 —
Budget approval action stays unwired...") for the full reasoning.

### Phase 17 — Community Heatmap, Rate Resolution, Appeal Rejection (SRS 16.1) — Explicitly left open

Found by this phase's endpoint audit; no backend endpoint exists for any
of the three anywhere in this codebase. All three need real backend
design work (new request/response contracts, and persistence for
ratings) before any Flutter screen could integrate against them — this
is different in kind from this phase's own Authentication Module work,
which only needed to catch Flutter up to an already-complete backend
contract. See `PROJECT_INTEGRATION.md` Section 6 ("Phase 17 — Community
Heatmap, Rate Resolution, and Appeal Rejection...") for the full
reasoning.

### Phase 15 — SRS 15.13 Exceptions vs. 20.5 API contract conflict over `email_enabled` — RESOLVED this phase (Exceptions clause treated as binding, documented)

Same category of self-conflicting SRS text as Phase 14's duplicate-
merge-vs-review threshold question below. SRS 15.13's Exceptions clause
says in-app and email are mandatory for status-transparency purposes
regardless of opt-out, but the 20.5 API table still lists `email_enabled`
as a real toggle. Resolved by making the Exceptions clause binding for
the three trigger methods this phase adds (status-change/officer-
assignment/SLA-warning alerts always send EMAIL) while still keeping
`email_enabled` as a real, working preference field for the API contract
to remain honest - see `PROJECT_INTEGRATION.md` Section 6 ("Phase 15 —
IN_APP and EMAIL are mandatory for major alerts...") for the full
reasoning.

### Phase 15 — Real push notification delivery — Explicitly left open (structural gap, not a scheduling one)

`push_enabled` is a real, working preference (stored/returned/
updatable), but no `PUSH` notification is ever actually dispatched -
this schema has no device-token (FCM/APNs) storage anywhere, so there is
nothing to send a push notification *to*. Building real push support
needs, at minimum: a new device-token table/column with its own
migration, a Flutter-side registration call, Flutter FCM SDK integration
(new native dependency + platform config + a Firebase project this
workspace cannot provision), and `NotificationService`'s dispatch path
wired to call it for real. Left open for whichever future phase actually
needs push delivery to work - not assumed to be Phase 16 (Dashboards)
specifically, since nothing about dashboards requires it. See
`PROJECT_INTEGRATION.md` Section 6 ("Phase 15 — PUSH is a real,
persisted preference that is never actually dispatched") for the full
reasoning and exactly what's missing.

### Phase 15 — Should notification dispatch move to an async queue? — Explicitly left open

`NotificationService.dispatch` runs synchronously on the caller's own
request thread (or the escalation scheduler's thread), including its
retry/backoff loop. No message broker exists anywhere in this stack, and
introducing one is a genuine infrastructure decision (which broker, how
it's deployed, how failures/dead-letter handling work) well outside this
phase's "Notifications & Personal Settings" scope. Left open for
whichever future phase needs to address real-world delivery latency at
volume - see `PROJECT_INTEGRATION.md` Section 6 ("Phase 15 —
Notification dispatch is synchronous...") for the full reasoning.

### Phase 16 — Officer Dashboard (SRS 24.2) and Citizen Dashboard (SRS 24.1) — Explicitly left open

Neither screen was built this phase - see `PROJECT_INTEGRATION.md`
Section 6 ("Phase 16 — Officer Dashboard and Citizen Dashboard are out
of scope...") for the full reasoning behind treating SRS 16.3's
screen-level permission line as authoritative over SRS 15.10's more
general Officer-inclusive business-rule text. When a future phase does
build the Officer variant, it should reuse
`AnalyticsAggregationService#computeKpiTiles(departmentId)` for the
department-average comparison SRS 24.2 asks for ("personal-vs-
department performance"), rather than re-deriving the same KPI formula
a second time - see `PROJECT_PROGRESS.md`'s INTEGRATION REQUIREMENTS
FOR NEXT PHASE.

### Phase 16 — Real infrastructure/application monitoring (SRS Section 20) — Explicitly left open

SRS Section 20 describes CPU/memory/request-latency/error-rate/queue-
depth metrics "visualized on an operations dashboard" - no phase in
Section 8's Phase → Component map owns building this yet. Phase 18
(`docker/`) has now run and deliberately did not build this (see
`docker/README.md`'s "What isn't containerized" section) - Phase 18's
own scope was containerizing the three existing components, not adding
a new Prometheus/Grafana stack and a new backend dependency
(`micrometer-registry-prometheus`, not in `backend/pom.xml`) on top of
them. Phase 22 `deployment/` remains the closest still-unbuilt
candidate. This phase's (Phase 16's) Admin Dashboard "system health
indicators" remains a documented substitution (notification-delivery
failure rate + current SLA-breach count), not an implementation of the
SRS's literal ask - see `PROJECT_INTEGRATION.md` Section 6 ("Phase 16 —
'System health indicators'...") for the full reasoning and exactly what
a future Monitoring-owning phase would need to add.

### Phase 18 — Root `.env.example` and `.gitignore` did not exist before this phase — resolved by recreating both, sourced from current config, not old history

Both files are referenced by name throughout the codebase and by their
own file paths in this file and `README.md`'s repository-layout diagram
since Phase 1, and `PROJECT_INTEGRATION.md` Section 6 has entries dated
Phase 4 and Phase 8 describing edits made *to* a root `.env.example` -
but neither file existed anywhere in the Phase 17 baseline ZIP this
phase started from, confirmed by a full recursive filesystem search
before writing any Docker file. Whether this is a packaging omission in
some intervening phase's deliverable ZIP or the file was reverted by
some phase that isn't logged as having touched it cannot be determined
from this project's own historical record - the Phase 17 ZIP is this
project's only real state, and it does not have these files. Resolved
by recreating both fresh this phase, with every default value sourced
from the current, live, authoritative config (`application.yml`'s own
`${VAR:default}` syntax, `ai-service/app/config.py`, `ai-service/.env.example`)
rather than attempting to reconstruct the old file's exact prior content
from `PROJECT_INTEGRATION.md`'s historical prose, which cannot be
verified against source that no longer exists. One naming
inconsistency from that old history was NOT carried forward on purpose:
a Phase 8 entry mentions an old "`AI_API_BASE_URL` (the unused
Flutter-section entry...)" name, but `flutter/lib/core/api/api_config.dart`'s
own current doc comment (Phase 6, still accurate today) names the
convention `API_BASE_URL` - the live source file was treated as
authoritative over the unverifiable old history.

### Phase 18 — Flutter deliberately not containerized

Docker containers run long-lived server processes; Flutter compiles to
a mobile app (Android/iOS) that runs on a device or emulator, not inside
a container, under every screen and API client this project has built
since Phase 6. See `docker/README.md`'s "What isn't containerized"
section for the full reasoning, including the explicit note that a
future Flutter *web* build target would be a genuinely new deliverable,
not something implied by this phase's `docker/` component-map
assignment.

### Phase 16 — Full Reports Module (SRS 15.12: PDF export, scheduled email delivery, multiple report types) — Explicitly left open

CSV-only export was implemented this phase, following Phase 13's exact
precedent - see `PROJECT_INTEGRATION.md` Section 6 ("Phase 16 — The
full Reports Module (SRS 15.12) stays out of scope..."). A future
Reports-owning phase should build on top of
`AnalyticsAggregationService`'s existing computation methods (KPI
tiles, department comparison, admin summary) rather than re-deriving
the same aggregations a second time for its own report generation.

### Phase 16 — Should the analytics cache move to a shared/external store? — Explicitly left open

`AnalyticsCacheService`'s in-process cache does not survive a JVM
restart and would not be correctly shared across multiple horizontally-
scaled backend instances. Acceptable for this project's current single-
instance scope (Section 7's non-goal against introducing a cache layer
without a demonstrated technical requirement) - left open for whichever
future phase actually introduces horizontal scaling, at which point a
shared store (Redis or equivalent) would become a real, demonstrated
requirement rather than a speculative one. See `PROJECT_INTEGRATION.md`
Section 6 ("Phase 16 — The nightly analytics cache is in-process...")
for the full reasoning.

### Phase 14 — Duplicate-merge-vs-review threshold conflict rule (SRS 15.15 Exceptions) — RESOLVED this phase (treated as inapplicable, documented)

SRS 15.15's Exceptions line names a conflict check ("duplicate-merge
threshold higher than duplicate-review threshold") this schema has no
basis for - `RoutingRule` (V14) carries a single
`duplicate_similarity_threshold` field, not separate merge/review ones.
Rather than leave this open or invent a second field just to satisfy one
Exception line, Phase 14 resolved it by treating the rule as
inapplicable to this schema and documenting why - see
`PROJECT_INTEGRATION.md` Section 6 ("Phase 14 — The SRS's duplicate-
merge-vs-review threshold conflict rule doesn't apply to this schema")
and `PlatformSettingKey`'s own class Javadoc "SCOPE NOTE".

### Phase 14 — Routing screen's "Revert to Default" action — RESOLVED this phase (reinterpreted as row deactivation, documented)

Same category of question as Phase 13's SLA-formula one above: SRS 16.3
names an action ("Revert to Default") the underlying schema (Phase 11's
supersede-by-new-row `routing_rules` design, V14) has no literal concept
for - there is no separate "default row" to revert to. Phase 14 resolved
this by adding an explicit `deactivate` action instead, which - given
`RoutingRuleRepository#findCurrentActiveRule`'s "latest active row"
resolution - causes the next-latest active row to become current when
the current one is deactivated, the closest honest analog available. See
`PROJECT_INTEGRATION.md` Section 6 ("Phase 14 — Routing rule
'deactivate' is the closest available analog...") and
`RoutingRuleService`'s class Javadoc for the full reasoning.

### Phase 13 — Should classification override re-trigger priority/budget prediction? — Still explicitly left open

Phase 12 flagged this as a candidate to resolve in "Phase 13, or a
dedicated revisit." Phase 13's SRS re-reading focused specifically on
the Department Head Module (16.2) and did not surface new evidence
either way for this question, which belongs to the classification-
override action (Phase 12), not anything Phase 13 built. Phase 14's own
SRS re-reading (Admin & Settings Module, 15.11/15.15) likewise surfaced
no new evidence either way - still left open, unchanged - see Phase 12's
entry immediately below for the full still-current record. The
prediction call itself still exists and is still a one-line addition to
`ComplaintService.overrideClassification` if a future phase's SRS
reading determines it's required.

### Phase 13 — SLA-compliance-% and average-resolution-time: no SRS-specified formula — RESOLVED this phase (project-defined, documented)

SRS 16.2/15.10/24.4 name these as KPIs/chart content but never define
either formula. Rather than leaving this open for a future phase (as
Phase 12 did for the classification-override question above), Phase 13
resolved it immediately, since the Department Performance View could not
otherwise be built at all - see `PROJECT_INTEGRATION.md` Section 6
("Phase 13 — SLA-compliance-% and average-resolution-time formulas are
this project's own choice, not verbatim SRS text") for both formulas and
the reasoning behind each. A future Phase 16 (Dashboards) building the
broader Government Dashboard should reuse these same formulas/shapes
rather than re-deriving different ones, to keep the two screens'
reported numbers consistent for the same underlying data.

### Phase 13 — `reassign()`/`approveBudget()` department-scope gap — RESOLVED this phase

Not a question left open by any prior phase's own writing - a genuine
gap discovered during Phase 13's own inspection of the existing
`ComplaintService` before adding new code on top of it (this project's
standard "inspect actual project structure, not prior summaries" step).
`reassign()` and `approveBudget()` (both Phase 11) had shipped with no
department-scope enforcement at all. Closed this phase by reusing the
existing `requireCanView` helper plus one additional own-department
check on `reassign()`. See `PROJECT_INTEGRATION.md` Section 6 for the
full writeup, including the one deliberate edge-case consequence for a
`VERIFIED`-with-null-department complaint.

### Phase 12 — Should the Officer/Department Head queue restriction be a default or a hard restriction? — RESOLVED this phase

Phase 11 left `ComplaintRepository.findForStaff` unrestricted for every
non-citizen role and explicitly flagged the officer/department-head
"own queue" narrowing as work for "whichever phase builds the actual
officer/department-head-facing queue UI" (PROJECT_INTEGRATION.md's Phase
6 entry, "PARTIALLY SUPERSEDED Phase 11").

**Resolution (Phase 12):** hard restriction, not merely a default. A
GOVERNMENT_OFFICER's `departmentId`/scope cannot be widened by any
request parameter — the server ignores a caller-supplied `departmentId`
for that role entirely rather than treating it as an optional filter on
top of an already-broad default. This matches this project's established
"scoped server-side, not merely hidden client-side" principle
(Section 5) applied for the first time to the officer-facing queue. See
`PROJECT_INTEGRATION.md` Section 6 ("Phase 12 — Officer/Department Head
queue scoping is now a hard server-side default") for the full record.

### Phase 12 — Should classification override re-trigger priority/budget prediction? — Explicitly left open

SRS 15.8/16.2 describe the override action itself (category/severity +
justification) but do not explicitly state whether correcting severity
after assignment should cause the budget/priority estimate to be
recomputed. This phase implements the override without re-triggering
`PriorityBudgetPredictionService` — a deliberate, documented scope
boundary rather than a silent decision either way. Left open for a
future phase (Department Head Module, Phase 13, or a dedicated revisit)
to resolve with a fresh SRS reading; the prediction call itself already
exists and is a one-line addition to `ComplaintService.
overrideClassification` if a future phase determines it's required. See
`PROJECT_INTEGRATION.md` Section 6 for the full record.

### Phase 11 — Five Department Assignment implementation choices SRS 15.7 left open — RESOLVED this phase

SRS 15.7 specifies Department Assignment's Purpose/Features/Business
Rules/Exceptions in full, but several concrete implementation choices
weren't fully determined by that text alone: how routing rules actually
get created (the module needs at least one to route by anything other
than the fallback), whether officer load-balancing should also consider
ward, what endpoint shape manual reassignment takes, whether SLA-breach
escalation is in this phase's scope given no scheduler infrastructure
existed yet, and whether the SRS 15.9 budget-approval gate (deliberately
deferred in Phase 10) should now be built.

**Resolution (Phase 11):** all five were asked and answered before
coding, not assumed:
1. A minimal Admin routing-rule create+list endpoint was added this
   phase (ahead of the full Admin Module, Phase 14).
2. Officer load-balancing is department-scoped only, not ward-scoped.
3. Manual reassignment got its own dedicated endpoint
   (`PATCH .../assign`), not folded into the generic status action.
4. SLA-breach escalation is in scope — a real `@Scheduled` sweep was
   built (`EscalationSchedulerService`), not deferred.
5. The budget-approval-threshold gate is now implemented and enforced.

Full reasoning and the exact question/answer record for each:
`PROJECT_INTEGRATION.md` Section 6 ("Phase 11 — Five architecture
decisions confirmed with the user before coding").

### Phase 10 — Where exactly should severity/priority/budget prediction run in the workflow? — RESOLVED this phase

Phase 9's own forward-looking note (`PROJECT_PROGRESS.md`
"INTEGRATION REQUIREMENTS FOR NEXT PHASE") speculated this module would
"likely need to run AFTER duplicate-checking in `AiClassificationService`'s
sequence," in the same synchronous chain as classify/duplicate-check, and
flagged it as worth confirming against SRS 15.3's workflow diagram before
committing to that shape.

**Resolution (Phase 10):** SRS 14.1/14.2's own workflow diagram (not
15.3, which only covers the Complaint Module's own state machine) places
"Severity/Priority/Budget Prediction" after the "Verified or Manual
Verification Queue" split — i.e. once a complaint has a real, settled
category and is confirmed not a duplicate, which is exactly what reaching
`VERIFIED` means, under either the automated or the manual-verify path.
`PriorityBudgetPredictionService.predictAndApply` is therefore called from
both places a complaint can reach `VERIFIED`
(`AiClassificationService.applyAutoVerification` and
`ComplaintService.verify`'s `VERIFIED` branch), not from inside
`AiClassificationService.classifyAndRoute`'s classify/duplicate-check
sequence. This also cleanly handles the manual-verify path — a human
confirming a category and severity by hand still gets a computed priority
score and budget estimate, which the speculated "run right after
duplicate-check" shape would have missed entirely (that code path never
executes for a manually-verified complaint). See
`PROJECT_INTEGRATION.md` Section 6 for the full decision record.

### Phase 9 — `ai-service` needed a candidate pool for duplicate-checking but is architecturally stateless — RESOLVED this phase

SRS 15.6 Inputs requires comparing a new submission against "existing
open complaints in the same ward." `ai-service` has no MySQL access and
doesn't own the complaint record (Section 2.3, locked since Phase 1), so
it structurally cannot look that pool up itself — the same kind of gap
Phase 7 hit for `image_url` and Phase 8 resolved by having the caller
supply real data instead of growing a new capability on the AI side.

**Resolution (Phase 9):** `backend/.../DuplicateDetectionService` builds
the candidate pool (ward/status/time-window/self-exclusion query, capped
count) and sends each candidate's image + location + `created_at`
alongside the new submission in the `/duplicate-check` request body — an
addition layered on top of SRS 20.3's literal contract, not a deviation
from `ai-service`'s stateless architecture. Full reasoning:
`ai-service/app/schemas/duplicate_check.py`'s module docstring;
integration record: `PROJECT_INTEGRATION.md` Section 6.

### Phase 7 — `ai-service` has no fetchable image URL to call the backend with yet — RESOLVED Phase 8

SRS 20.3's literal `/api/v1/ai/classify` contract expects `image_url`.
Phase 6's `LocalStorageService` issues no public/pre-signed URL for a
stored photo (`ImageResponse` deliberately omits `storageKey`), and this
project has no real S3 yet either. Phase 7 added an `image_base64`
alternate input as a Phase-7-only testing convenience so the endpoint
could be exercised at all pending Phase 8.

**Resolution (Phase 8):** the backend calls `ai-service` with the image
bytes directly, not a URL. `StorageService` (Phase 6) gained a
`load(storageKey)` method; `AiClassificationService` reads the stored
photo, base64-encodes it, and always sends `image_base64` — the
URL-issuing option was rejected as a bigger, riskier change to a
component explicitly documented as a local-disk S3 stub, for a capability
this project has no other need for yet. `image_url` remains implemented
and correct on the `ai-service` side (unmodified), simply unused. Full
reasoning: `PROJECT_INTEGRATION.md` Section 6 ("Phase 8 — Resolved the
`image_url`/`image_base64` open item").

### Phase 2 — SRS/BRD/FRS states a different tech stack than this document — RESOLVED at Phase 3 start

The real SRS/BRD/FRS was supplied in Phase 2 (it was not available in
Phase 1 — see that phase's KNOWN LIMITATIONS). Its Appendix A.2 states the
technology stack as **PostgreSQL, FastAPI (Python), React.js** — directly
contradicting Section 1/2 of this document (**MySQL, Java Spring Boot,
Flutter**), which was locked in Phase 1 before the real SRS existed.

**Resolution (Phase 3):** Phase 3's own explicit instruction — "Spring Boot
Foundation" — is treated as the team/stakeholder confirmation Phase 2
required before deepening this stack further. This document's locked stack
(MySQL, Java Spring Boot, Flutter) stands; the SRS's Appendix A.2 stack is
not adopted. Phase 3 built the full Spring Boot skeleton (13 JPA entities,
repositories, Flyway wiring, Maven build) on that basis — see
`PROJECT_INTEGRATION.md` Section 6 for the full record. If this reading is
wrong, it must be raised before Phase 4, since the Spring Boot/MySQL
investment is now substantial.

### Phase 6 — Flutter scaffolding: fulfilled, but intentionally narrow

Phase 5's note below flagged Flutter as a real open item, not an
oversight. Phase 6 fulfilled it: `flutter/` now has a real project
(`pubspec.yaml`, `lib/`), consuming the Phase 6 Complaint Module API. It
remains intentionally narrow — only a minimal login (not the full
Authentication Module's screen set) plus the Complaint Submission and
Complaint Tracking screens this phase was asked for. Full reasoning:
`PROJECT_INTEGRATION.md` Section 6, "Flutter scaffolded this phase; login
screen is intentionally minimal."

### Phase 5 — Flutter scope deferred by explicit instruction — RESOLVED at Phase 6

`flutter/` still contains only `.gitkeep`. Phase 4 (Auth) built no Flutter
screens despite ARCHITECTURE.md's own phase map implying otherwise for
later phases; Phase 5 was asked whether to begin Flutter scaffolding now
and was explicitly told "backend only this phase" — the Flutter app
(project init, auth screens, and citizen-module screens) is deferred to a
dedicated future phase, not started here. This is a real open item, not an
oversight: the Phase → Component map above is corrected to reflect it.

**Resolved (Phase 6):** see the note directly above — Flutter scaffolding
happened this phase, alongside the Complaint Module it was actually asked
to build screens for.

### Phase 2 — schema deviations from the SRS's own Database Design section

The SRS's Database Design tables (17-22) contain a few internal
inconsistencies (e.g. a Users.ward_id FK pointing at a column that doesn't
exist, a Predictions table missing a field its own Functional Requirements
section documents as an output, an ER cardinality that conflicts with its
own documented retry behavior). These were resolved with minimum-risk,
documented decisions rather than reproduced as-is. Full list:
`database/docs/data-dictionary.md`, "Decision notes" section.
