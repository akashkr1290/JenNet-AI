# JANNet AI — Integration Register

This file is the single place where cross-module contracts are recorded as
they're created, so Flutter, Spring Boot, Python, and MySQL never drift out
of sync silently. Update it whenever a contract is introduced or changed —
do not let API/DTO/schema changes happen without a corresponding entry here.

## 1. Integration Points (architectural, not yet implemented)

| From | To | Mechanism | Status |
|---|---|---|---|
| Flutter | Spring Boot | HTTPS REST, `/api/v1/...`, JWT bearer auth | **Implemented Phase 4**: `/api/v1/auth/**` and `GET /api/v1/users/me`. **Backend extended Phase 5** (no Flutter client yet at that point): `PUT /api/v1/users/me`, `GET /api/v1/wards`, `GET /api/v1/wards/{wardId}`. **Implemented Phase 6, both sides**: `flutter/` now exists (was `.gitkeep`-only through Phase 5) and consumes `/api/v1/complaints/**` (below) plus a minimal `/api/v1/auth/login` client — see Section 6. All other paths require a JWT bearer token (Spring Security, stateless). |
| Spring Boot | MySQL | Spring Data JPA | Schema exists (Phase 2); JPA entities + repositories mapped 1:1 onto `database/migrations/V1-V18` (Phase 6 added V18), `ddl-auto=validate` |
| Spring Boot | Local disk (photo storage) | `StorageService`/`LocalStorageService` | **Implemented Phase 6** as an explicit stub — see Section 5 |
| Spring Boot | AWS S3 | AWS SDK, pre-signed URLs | Not yet implemented. `StorageService` interface (Phase 6) is the swap-in seam; no `ComplaintService` change needed when a real `S3StorageService` lands. |
| Spring Boot | AI Service | HTTPS REST, `/api/v1/ai/...`, internal API key | **Implemented Phase 8/9/10**: `backend/.../client/ai/AiServiceClient` calls `POST /api/v1/ai/classify` (Phase 7 contract, Phase 8 wiring), `POST /api/v1/ai/duplicate-check` (Phase 9, SRS 20.3/15.6), and, as of Phase 10, `POST /api/v1/ai/priority-predict` (SRS 20.3/15.8) and `POST /api/v1/ai/budget-predict` (SRS 20.3/15.9) — all via `AiClassificationService`/`DuplicateDetectionService`/`PriorityBudgetPredictionService`, wired into `ComplaintController.create` (classify+duplicate-check) and the `VERIFIED` transition (priority+budget, both the auto-verify and manual-verify paths) — see Section 2 and Section 6. Phase 6's manual Verification Team override (`PATCH /api/v1/complaints/{id}/verify`) remains available afterward as the fallback/QA path (never removed) and as the *only* path forward whenever ai-service is down, disabled, returns `requires_manual_review: true`, or flags a borderline (60-80%) possible duplicate for confirmation. All four `/api/v1/ai/*` endpoints in SRS 20.3's contract table are now implemented and consumed. |
| AI Service | Gemini API | Google Gemini SDK/HTTPS | **Implemented Phase 7** (`app/services/gemini_service.py`) — real call code, but never exercised in this workspace (no network, no `GEMINI_API_KEY` configured by default); falls back to YOLOv11-only with a capped confidence on any failure, per SRS 15.4 Exceptions. See Section 6. |
| AI Service | YOLOv11 model | Ultralytics `.pt` weights, local file load | **Model-loading architecture implemented Phase 7** (`app/services/yolo_service.py`); **no trained weights ship with this repo** — every call reports `model_available: false` until one is placed. See `ai-service/models/README.md` and Section 6. |
| Spring Boot | SMS/Email (OTP delivery) | SMS_PROVIDER_*/SMTP_* | **Stubbed Phase 4** — `LoggingOtpDeliveryService` logs the OTP instead of sending it; real provider integration is Phase 15 (Notification Module) |
| Spring Boot | Spring Boot (internal, scheduled) | `@Scheduled` fixed-delay job | **Implemented Phase 11**: `EscalationSchedulerService.sweepForSlaBreaches` — the first in-process scheduled job this backend runs (`@EnableScheduling` added to `BackendApplication`). No external system involved; listed here because it's a new *kind* of integration point (time-triggered, not request-triggered) worth recording alongside the request/response ones above. |
| Docker Compose | Spring Boot / AI Service / MySQL | Container network (`docker/docker-compose.yml`'s `jannet-network` bridge) | **Implemented Phase 18**: all three of the above rows' hostnames/ports (`localhost`/configurable in every prior phase's `.env`) resolve inside Compose to the service names `backend`/`ai-service`/`mysql` instead — `AI_SERVICE_BASE_URL=http://ai-service:8001` and `DB_HOST=mysql` in the root `.env.example`, both already-existing env-var seams from Phase 4/8, needed no code change to work inside a container network. `backend` startup is gated on `mysql`'s healthcheck (Flyway needs a live connection immediately on boot); `ai-service` has no such gate since `AiClassificationService` already tolerates it being unreachable at request time. See `docker/README.md`. |

## 2. API Contract Convention (locked in Phase 1)

- Business backend base path: `/api/v1/...`
- AI service base path: `/api/v1/ai/...`
- Backend documents all endpoints via OpenAPI/Swagger — the generated spec
  is the source of truth Flutter codegens/consumes against.
- Standard error response shape (**finalized Phase 3** —
  `backend/src/main/java/com/jannetai/backend/exception/ErrorResponse.java`,
  wired via `GlobalExceptionHandler`; every controller added by later
  phases inherits this automatically):

```json
{
  "timestamp": "ISO-8601 string",
  "status": 400,
  "error": "BAD_REQUEST",
  "message": "human-readable message",
  "path": "/api/v1/...",
  "details": []
}
```

- **Implemented Phase 4 — Authentication Module endpoints** (401/403 from
  Spring Security also use this shape, via `RestAuthenticationEntryPoint`/
  `RestAccessDeniedHandler`):

| Endpoint | Method | Auth | Notes |
|---|---|---|---|
| `/api/v1/auth/register` | POST | public | 201; CITIZEN only — staff accounts are Admin-provisioned (Phase 14) |
| `/api/v1/auth/verify-otp` | POST | public | mobile verification (purpose=REGISTRATION) |
| `/api/v1/auth/resend-otp` | POST | public | any OTP purpose |
| `/api/v1/auth/login` | POST | public | 200 with `AuthResponse`, or 200 with `{mfaRequired:true, mfaToken}` for ADMIN/SUPER_ADMIN |
| `/api/v1/auth/mfa/verify` | POST | public | completes MFA login, returns `AuthResponse` |
| `/api/v1/auth/refresh` | POST | public | single-use rotation; reuse revokes the whole token family |
| `/api/v1/auth/logout` | POST | public | revokes one refresh-token family (this session) |
| `/api/v1/auth/logout-all` | POST | JWT | revokes every session for the caller |
| `/api/v1/auth/forgot-password` | POST | public | always 200, generic message (no account enumeration) |
| `/api/v1/auth/reset-password` | POST | public | OTP-gated; revokes all existing sessions on success |
| `/api/v1/users/me` | GET | JWT | any authenticated role |

- **Implemented Phase 5 — Citizen Module endpoints (SRS 15.1)**:

| Endpoint | Method | Auth | Notes |
|---|---|---|---|
| `/api/v1/users/me` | PUT | JWT | Self-service profile update: `fullName`, `wardId` only. Does NOT accept `mobileNumber`/`email` — see decision below. `wardId: null` clears the ward; a non-null `wardId` must reference an active ward or 404s. |
| `/api/v1/wards` | GET | JWT | Lists active wards (`wardId`, `name`, `code`), ordered by name. |
| `/api/v1/wards/{wardId}` | GET | JWT | Single active ward; 404 if missing or inactive. |

  `AuthResponse`: `{ accessToken, refreshToken, expiresInSeconds, user }`.
  `user` is `UserProfileResponse` (userId, fullName, mobileNumber, email,
  role, status, reputationScore, wardId, departmentId) — never includes
  passwordHash/failedLoginCount/lockedUntil.

- **Implemented Phase 6 — Complaint Module endpoints (SRS 15.3 / Table 23)**:

| Endpoint | Method | Auth | Notes |
|---|---|---|---|
| `/api/v1/complaints` | POST | CITIZEN | multipart/form-data: `photo` (required, JPEG/PNG/WEBP, ≤10MB), `description` (optional, ≤500 chars), `latitude`/`longitude` (required), `wardId`/`locationSource` (optional). 201. Returned/persisted status is `AI_PROCESSING`, not `SUBMITTED` — see decision below. |
| `/api/v1/complaints/{id}` | GET | JWT | Citizen: own complaints only (403 otherwise). Any staff role: unrestricted (still not query-level department-scoped, even after Phase 11 — see PROJECT_PROGRESS.md "INTEGRATION REQUIREMENTS FOR NEXT PHASE"; `complaint.department` is now populated once ASSIGNED, but visibility filtering itself wasn't narrowed this phase). |
| `/api/v1/complaints` | GET | JWT | Query: `status`, `category`, `departmentId` (staff only), `page`, `pageSize`. Citizen-scoped or staff-scoped per role, same visibility rule as GET /{id}. |
| `/api/v1/complaints/{id}/verify` | PATCH | VERIFICATION_TEAM, ADMIN, SUPER_ADMIN | The approved manual override for the pre-AI-service gap. Body: `{decision: VERIFIED\|REJECTED\|DUPLICATE, category?, severity?, rejectionReasonCode?, parentComplaintId?, note?}` — field requirements depend on `decision`, see `VerificationDecisionRequest`'s Javadoc. Only legal while status is `AI_PROCESSING`. **Phase 10**: on a `VERIFIED` decision, a supplied `severity` always wins over the AI's own Priority Prediction Module output (applied immediately, unconditionally); `PriorityBudgetPredictionService` still runs afterward regardless, to compute the numeric priority score and a budget estimate. **Phase 11**: immediately after that, `DepartmentAssignmentService` runs too — routes to a department (routing-rule match or "General Triage" fallback) and load-balances to an officer, transitioning `VERIFIED -> ASSIGNED` — see Section 6. |
| `/api/v1/complaints/{id}/reopen` | POST | CITIZEN | Own complaint only; only from RESOLVED/CLOSED; 409 once the configured grace period has passed. |
| `/api/v1/complaints/{id}/status` | PATCH | GOVERNMENT_OFFICER, MAINTENANCE_TEAM, DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN | Generic downstream transition (SRS Table 23). **Phase 11**: now actually reachable past VERIFIED — a complaint auto-assigns to ASSIGNED the moment it's verified (see `/verify` row above), so ASSIGNED -> IN_PROGRESS -> RESOLVED -> CLOSED are all real, exercisable transitions for the first time. **New this phase**: ASSIGNED -> IN_PROGRESS is blocked with 409 `BUDGET_APPROVAL_REQUIRED` when the complaint's budget estimate exceeds the configured threshold and no Department Head has approved it yet (SRS 15.9) — see `/approve-budget` below and Section 6. |
| `/api/v1/complaints/{id}/assign` | PATCH | DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN | **New Phase 11** (SRS 15.7 Features: "manual reassignment by Admin or Department Head"). Body: `{departmentId, officerId?, note?}`. Deliberately separate from `/status` — changes `department`/`assignedOfficer` directly, independent of any status transition; also performs the one-time `VERIFIED -> ASSIGNED` transition if the complaint hadn't auto-assigned yet. `officerId` must be a `GOVERNMENT_OFFICER` belonging to `departmentId`, or omitted for department-level-only assignment. |
| `/api/v1/complaints/{id}/approve-budget` | PATCH | DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN | **New Phase 11** (SRS 15.9). No body. Sets `budget.approved_by` on the complaint's current budget estimate, unblocking a subsequent `ASSIGNED -> IN_PROGRESS` `/status` call that the threshold gate would otherwise refuse. Idempotent — approving an already-approved or never-gated estimate is harmless. |

- **New Phase 11 — Department Assignment Module endpoints (SRS 15.7 / 17.4 / 20.4)**:

| Endpoint | Method | Auth | Notes |
|---|---|---|---|
| `/api/v1/departments` | GET | JWT (any authenticated role) | Lists active departments (`departmentId`, `name`, `description`, `headUserId`, `isActive`), ordered by name. SRS 20.4's literal contract. Read-only — no create/edit endpoint (departments are fixed civic-department seed data, V15). |
| `/api/v1/admin/routing-rules` | POST | ADMIN, SUPER_ADMIN | SRS 17.4's "Admin — Routing Rule Form". Body: `{issueCategory, departmentId, aiConfidenceThreshold?, duplicateSimilarityThreshold?, slaHours, effectiveFrom?}` — the two threshold fields default to 85.00/80.00 (Table 10's own Default Value column) when omitted; `effectiveFrom` defaults to today. 201 with the created rule. Deliberately minimal — create + list only, no update/deactivate (superseding a category's routing means creating a new row with a later `effectiveFrom`; the "latest active row" resolution already handles this) — see Section 6 for why this exists this phase, ahead of the full Admin Module (Phase 14). |
| `/api/v1/admin/routing-rules` | GET | ADMIN, SUPER_ADMIN, DEPARTMENT_HEAD | Lists all currently-active routing rules, ordered by category. |

  `RoutingRuleResponse`: routingRuleId, issueCategory, departmentId,
  departmentName, aiConfidenceThreshold, duplicateSimilarityThreshold,
  slaHours, effectiveFrom, isActive, createdByUserId, createdAt.
  `DepartmentResponse`: departmentId, name, description, headUserId,
  isActive.



  `ComplaintResponse` (detail): complaintId, referenceNumber, citizenId,
  category, description, location (LocationResponse), departmentId,
  assignedOfficerId, status, severity, parentComplaintId,
  corroborationCount, isEscalated, escalatedAt, isReopened, reopenedAt,
  rejectionReasonCode, images (ImageResponse[] — no storageKey, see
  Section 5), statusHistory (StatusHistoryResponse[]), createdAt,
  updatedAt. `ComplaintSummaryResponse` (list view) is a lighter subset
  with no images/statusHistory.

- **AI Service endpoints (`ai-service/`, SRS 20.3 / 15.4 / 15.6 / 15.8 /
  15.9) — built Phase 7 (`/classify`), Phase 9 (`/duplicate-check`), and
  Phase 10 (`/priority-predict`, `/budget-predict`); all four consumed by
  the backend (Phase 8, 9, and 10 respectively)**:

  Base path `/api/v1/...`, a **separate Python FastAPI process**, not a
  Spring Boot controller. Auth is a shared internal API key
  (`X-Internal-Api-Key` header), not JWT — the caller is the backend
  itself, not an end user. **`/classify` consumed Phase 8, `/duplicate-
  check` consumed Phase 9, `/priority-predict`+`/budget-predict` consumed
  Phase 10** — `backend/.../client/ai/AiServiceClient` calls all four from
  `AiClassificationService`/`DuplicateDetectionService`/
  `PriorityBudgetPredictionService`; see Section 1 and Section 6. Every
  row below is now implemented and consumed; none remain placeholders.

| Endpoint | Method | Auth | Notes |
|---|---|---|---|
| `/api/v1/ai/classify` | POST | Internal API key | Body: `image_url` **or** `image_base64` (exactly one — see decision below), optional `description`, `prior_model_version`, `complaint_id`. 200 with the full result (see below) whether or not the image auto-qualifies — a low-confidence result is still a 200, not an error. 422 if the image fails the OpenCV quality gate (`UNPROCESSABLE_IMAGE`) or the request is malformed. 504 on a model inference timeout (`MODEL_TIMEOUT`) — not currently reachable since no model is loaded to time out; wired for when one is. 401 on a missing/invalid API key. **Called from Phase 8** with `image_base64` always (never `image_url` — see decision below), right after `ComplaintService.create` commits a complaint at `AI_PROCESSING`. |
| `/api/v1/ai/duplicate-check` | POST | Internal API key | Body (SRS 20.3 literal fields plus an ADDITION — see decision below): `complaint_id`, `image_url` **or** `image_base64`, `latitude`/`longitude`, plus `candidates` (a list of existing open complaints to compare against, each with its own image/lat/lon/`created_at` — supplied by the backend since ai-service is stateless with respect to business data). 200 with `is_duplicate`, `parent_complaint_id`, `similarity_score` (SRS 20.3 literal output) plus `requires_manual_review`/`match_tier`/`threshold_used`/`gps_available`/`top_matches`/`raw_output` (documented additions, same pattern as `/classify`'s `requires_manual_review`). An empty `candidates` list is valid (200, `match_tier: NO_CANDIDATES`), not an error. 422 if the *new submission's own* image fails to decode; a bad *candidate* image is silently skipped instead (recorded in `raw_output.skipped_candidates`), never fails the request. 401 on a missing/invalid API key. **Called from Phase 9** right after a successful `/classify` call, via `DuplicateDetectionService`. |
| `/api/v1/ai/priority-predict` | POST | Internal API key | Body (SRS 20.3 literal fields plus an ADDITION — see decision below): `category`, `corroboration_count`, `location_flags` (`{near_school, near_hospital, high_traffic_road}` — always all-`false` from this backend today, see decision below), plus `complaint_id` (audit only). 200 with `severity`, `priority_score` (SRS 20.3 literal output) plus `safety_hazard_override_applied`/`corroboration_bump_applied`/`base_severity`/`model_version`/`raw_output` (documented additions, same pattern as `/classify`'s `requires_manual_review`). Deterministic rules-based scoring, never a training-data-driven prediction — see decision below. No error responses beyond the standard 401; a well-formed request against this static table cannot itself fail validation-wise beyond FastAPI's own 422 for a malformed body. **Called from Phase 10** the moment a complaint reaches `VERIFIED` (both the auto-verify and manual-verify paths), via `PriorityBudgetPredictionService`, always before `/budget-predict`. |
| `/api/v1/ai/budget-predict` | POST | Internal API key | Body (SRS 20.3 literal fields): `category`, `severity` (the complaint's FINAL severity — staff override if supplied, else `/priority-predict`'s own output; never computed independently), `ward_id` (accepted for audit/future-use only, no ward-level historical data exists yet — see decision below), plus `complaint_id` (audit only). 200 with `estimated_cost_min`, `estimated_cost_max`, `estimated_resolution_days`, `confidence` (SRS 20.3 literal output; `confidence` is always `"PRELIMINARY"` this phase, see decision below) plus `model_version`/`guardrail_applied`/`raw_output` (documented additions). No error responses beyond the standard 401/422. **Called from Phase 10** immediately after `/priority-predict` succeeds, via `PriorityBudgetPredictionService`. |
| `/health` | GET | none | ADDITION beyond the SRS 20.3 literal contract — standard liveness/readiness probe, unauthenticated by design. Reports `model_available`. |

  `ClassifyResponse`: `category` (matches backend `ComplaintCategory`
  value-for-value — see Section 3), `confidence` (0-100),
  `gemini_description`, `ocr_text`, `preprocessed_image_reference`
  (always `null` this phase — no storage integration yet, see decision
  below), plus — **additions beyond SRS 20.3's literal 4-field output list,
  needed for Phase 8 to actually implement the SRS 15.4 routing rule** —
  `requires_manual_review` (bool), `routing_reason` (string; see Section 3
  for the fixed set of values), `model_version`, `model_available` (bool),
  `image_quality_flag`, `gemini_used` (bool), `top_candidates` (array,
  ≤3), `raw_model_output` (full JSON, maps onto `predictions.raw_model_output`).

  `PriorityPredictResponse` (Phase 10): `severity` (`LOW|MEDIUM|HIGH|
  CRITICAL`), `priority_score` (0-100, SRS 20.3 literal output) plus
  `safety_hazard_override_applied`/`corroboration_bump_applied`/
  `base_severity`/`model_version`/`raw_output` (documented additions).
  `BudgetPredictResponse` (Phase 10): `estimated_cost_min`,
  `estimated_cost_max`, `estimated_resolution_days`, `confidence`
  (`PRELIMINARY|STANDARD|HIGH`, SRS 20.3 literal output; always
  `PRELIMINARY` this phase) plus `model_version`/`guardrail_applied`/
  `raw_output` (documented additions).

  **Error envelope for this service only** — deliberately different shape
  from Section 2's Spring Boot `ErrorResponse` above:
  `{ "error_code": string, "message": string, "details": object|null }`.
  This is SRS 20.6's own literal envelope for this service; see decision
  below for why it isn't reconciled with the Java backend's shape.

## 3. Shared Enums (to be finalized as each owning phase lands; placeholder set below so no module diverges)

- **OTP purpose** (Phase 4, `otp_verifications.purpose`):
  `REGISTRATION, LOGIN_MFA, PASSWORD_RESET`
- **Complaint status** (unchanged 12-value list, locked Phase 1/reconciled
  Phase 2 — see `database/docs/data-dictionary.md` decision note 4):
  `DRAFT, SUBMITTED, AI_PROCESSING, VERIFIED, ASSIGNED, IN_PROGRESS,
  RESOLVED, CLOSED, DUPLICATE, REJECTED, ESCALATED, REOPENED`.
  **Phase 6 decision:** `DRAFT` is never persisted server-side (client-
  side-only concept per SRS Table 16 — see `ComplaintService.create`'s
  Javadoc), and `ESCALATED`/`REOPENED` are never set as a complaint's
  `status` either — both remain valid enum/CHECK values but are treated
  purely as annotations (`complaints.is_escalated`/`is_reopened` +
  timestamp columns already existed for exactly this from Phase 2/3; see
  `ComplaintStateMachine`'s class Javadoc for the full reasoning). Only
  10 of the 12 values are ever actually written to `complaints.status`
  in practice as a result: `SUBMITTED, AI_PROCESSING, VERIFIED, ASSIGNED,
  IN_PROGRESS, RESOLVED, CLOSED, DUPLICATE, REJECTED` (9), plus `DRAFT`
  and the two annotation-only values never appear at all.
  **Phase 11**: `ASSIGNED` is now genuinely reachable — previously listed
  among the 9 as a structurally-encoded-but-practically-unreachable value
  (`ComplaintStateMachine`'s Phase 6 note), it's the first status this
  phase's `DepartmentAssignmentService` writes for real, immediately
  after `VERIFIED`.
- **Roles** (updated Phase 2, was a 5-value placeholder — now matches the
  real SRS Section 11, which defines seven platform-account roles; the
  SRS's eighth role, "AI Processing Engine", is a system actor with no user
  row): `CITIZEN, GOVERNMENT_OFFICER, DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN,
  VERIFICATION_TEAM, MAINTENANCE_TEAM`
- **ActorType** (`status_history.actor_type` — a distinct, smaller value
  set from `Role`, see `ActorType`'s own Javadoc for the mapping):
  `SYSTEM, CITIZEN, OFFICER, DEPARTMENT_HEAD, ADMIN, VERIFICATION_TEAM`.
  **Widened Phase 6** (`V18__widen_status_history_actor_type.sql`) to add
  `VERIFICATION_TEAM` — it was missing even though `Role.VERIFICATION_TEAM`
  has existed since Phase 2; see PROJECT_PROGRESS.md for the full record.
- **VerificationDecision** (new Phase 6, `dto/complaint` layer only — not
  a database column, purely the shape of `PATCH .../verify`'s request
  body): `VERIFIED, REJECTED, DUPLICATE`
- **AI issue categories** (updated Phase 2 — added `GENERAL` as the
  fallback category used by the Department Assignment Module's documented
  unmapped-category rule, SRS 15.7): `POTHOLE, GARBAGE_OVERFLOW,
  WATER_LEAKAGE, BROKEN_STREET_LIGHT, OPEN_MANHOLE, ILLEGAL_CONSTRUCTION,
  GENERAL`. **Phase 6**: every complaint is created with `GENERAL` (no
  real AI classification exists yet); the manual Verification Team
  override can set a real category on VERIFIED. **Phase 7**:
  `ai-service`'s `IssueCategory` (Python enum,
  `app/schemas/classify.py`) mirrors this list value-for-value on purpose,
  so Phase 8 can deserialize `ClassifyResponse.category` straight onto
  `ComplaintCategory` with no translation table.
- **Severity** (`complaints.severity`/`predictions.predicted_severity`,
  locked since Phase 2): `LOW, MEDIUM, HIGH, CRITICAL`. **Phase 10**:
  `ai-service`'s `priority_service.py` scoring table uses these same four
  plain-string values (no separate Python enum type — see that module's
  docstring), so the backend's `Severity` Java enum deserializes
  `PriorityPredictResponse.severity`/`base_severity` with no translation
  table, same pattern as `IssueCategory` above.
- **Budget confidence level** (`budget.confidence_level`, locked since
  Phase 2): `PRELIMINARY, STANDARD, HIGH`. **Phase 10**: every value
  produced this phase is `PRELIMINARY` — see PROJECT_INTEGRATION.md
  Section 6's "every budget estimate is honestly PRELIMINARY" decision.
- **Image quality flag** (new Phase 7, `ai-service` only —
  `app/schemas/classify.py: ImageQualityFlag`, not a database column):
  `ACCEPTABLE, BLURRY, TOO_DARK, TOO_SMALL`. `BLURRY`/`TOO_SMALL` reject
  the image at intake (422) before any model call; `TOO_DARK` is a
  non-rejecting flag only — see Section 6.
- **AI classification routing reason** (new Phase 7, `ai-service` only —
  `ClassifyResponse.routing_reason`, free-text-typed but drawn from a
  fixed set in `app/services/pipeline.py`): `MODEL_UNAVAILABLE,
  NO_DETECTION_ABOVE_THRESHOLD, GEMINI_DISAGREEMENT,
  BELOW_AUTO_APPROVE_THRESHOLD, AUTO_APPROVED`. The first four all imply
  `requires_manual_review=true`; only `AUTO_APPROVED` implies `false`.
  Phase 8 should treat this as informational/audit context, not branch
  logic — `requires_manual_review` alone is the field to branch on.

These live authoritatively in `shared/` once that phase populates it
(values must match exactly across Java enums, Python enums/constants, and
Flutter/Dart enums — any mismatch is an integration bug). Until then,
`database/migrations/` is the authoritative source for the exact string
values via its `CHECK` constraints.

## 4. Database ↔ Backend Boundary

- Team Member 4 owns `database/` — raw SQL, Flyway migration files, seed
  data, ER diagram, data dictionary.
- Team Member 1 owns the Java JPA entities/repositories in `backend/` that
  map onto that schema.
- Rule: the migration files are the schema's source of truth. JPA entities
  must be written/updated to match an already-migrated schema, not the
  other way around (no `ddl-auto=update` driving schema in any
  non-local/non-throwaway environment).
- **Implemented Phase 3:** all 13 tables have a corresponding JPA entity
  (`backend/src/main/java/com/jannetai/backend/entity/`) and repository
  (`.../repository/`), field-for-field verified against
  `database/migrations/`. `spring.jpa.hibernate.ddl-auto=validate` enforces
  the rule above at application startup — Hibernate fails fast if any
  entity mapping doesn't match the real schema, rather than silently
  altering it.
- `database/migrations/*.sql` is not duplicated into `backend/`; it's
  copied into the build's classpath at build time by a
  `maven-resources-plugin` execution in `backend/pom.xml`, so
  `database/migrations/` remains the single authored copy.
- **Implemented Phase 4:** added V16 (`otp_verifications`) and V17
  (`refresh_tokens`) with matching `OtpVerification`/`RefreshToken`
  entities and repositories — the Authentication Module needed persisted
  OTP and refresh-token state that didn't exist in V1-V15 (Phase 3 was
  skeleton-only). `AuditLogRepository` now extends a new
  `AppendOnlyRepository` (no delete method at all) instead of
  `JpaRepository`, closing the gap V12's own header comment flagged
  ("no DELETE-granting role should ever be given DELETE on this table
  ... enforced at the auth layer, Phase 4").
- **Implemented Phase 6:** added V18 (`ALTER TABLE status_history ...
  ADD CONSTRAINT`, widening `actor_type` to add `VERIFICATION_TEAM`) —
  the first migration to alter an already-shipped table rather than
  create a new one, following the DROP CHECK + ADD CONSTRAINT pattern
  V6/V10's own header comments established for this situation.
  `StatusHistoryRepository` now also extends `AppendOnlyRepository`
  (was plain `JpaRepository` since its Phase 3 skeleton), matching
  V10's own "append-only audit trail" header comment — the same gap
  `AuditLogRepository` closed in Phase 4, just not closed for this
  table until now.
- **Phase 11: no new migration.** `complaints.department_id`/
  `assigned_officer_id`/`is_escalated`/`escalated_at` (V6),
  `routing_rules` (V14), `budget.approved_by` (V9), and the "General
  Triage" fallback department seed row (V15) all pre-date this phase —
  Department Assignment is pure application-layer wiring on top of an
  already-correct schema, the same situation Phase 10 was in for
  `predictions`/`budget`.

## 5. Media Storage Contract

- Binary image/document data is never stored in MySQL.
- MySQL stores: object key/path, content type, size, uploader, upload
  timestamp, and (once the AI service tags it) linked AI-analysis metadata.
- S3 is the source of truth for the actual file bytes — **not yet
  implemented**; see below.
- Access to stored files goes through pre-signed URLs issued by the backend
  — buckets are not public.
- **Implemented Phase 6, as an explicit local-disk stub, not S3:**
  `StorageService` (interface) / `LocalStorageService` (implementation) —
  per this phase's explicit instruction ("clean storage abstraction/local
  stub for now; DO NOT require real AWS credentials"). `images.storage_key`
  (V7) is a relative local-disk path today, not an S3 object key, but the
  *contract* above (MySQL never holds bytes; `storage_key` is opaque to
  every caller) is honored — no caller distinguishes local-stub-backed
  vs. real-S3-backed storage. `ImageResponse` (the API-facing DTO)
  deliberately never exposes `storage_key` at all, consistent with "not
  public" above — the pre-signed-URL issuance step itself is NOT
  implemented this phase (`LocalStorageService` has no equivalent), so
  Flutter currently has no way to actually view a submitted photo, only
  its metadata (type/size/upload time). This is the one part of the
  contract genuinely unfulfilled right now, flagged rather than faked.
- **Phase 7 consequence, not yet resolved:** `ai-service`'s
  `POST /api/v1/ai/classify` literal contract (SRS 20.3) expects an
  `image_url` the service can fetch — but per the point directly above,
  nothing issues a fetchable URL for a locally-stubbed image yet. Phase 7
  therefore added an alternate `image_base64` input for standalone
  testing (Section 6) and left the real `image_url` path implemented but
  never exercised. **Whoever builds Phase 8 needs to resolve this
  properly** — either `LocalStorageService` grows a temporary/internal
  URL-issuing capability, or the backend reads the file itself and calls
  `ai-service` with `image_base64`/multipart instead of `image_url`. Not
  decided here; flagged for Phase 8 to decide with real integration code
  in hand rather than speculatively here.

## 6. Outstanding Integration Decisions

### Phase 11 — Five architecture decisions confirmed with the user before coding

SRS 15.7 (Department Assignment Module) left several genuine
implementation choices unresolved by its own text alone. Rather than
invent defaults, five questions were asked and answered before any Phase
11 code was written:

1. **Routing-rule management scope:** `routing_rules` has zero seeded
   rows and full Admin CRUD is Phase 14 scope per ARCHITECTURE.md's phase
   map — without any way to create a rule, every complaint would fall
   back to "General Triage" regardless of category. **Decided:** add a
   minimal Admin/SuperAdmin-only routing-rule create+list endpoint this
   phase (`POST`/`GET /api/v1/admin/routing-rules`), ahead of the full
   Admin Module, so real category->department mappings are actually
   configurable and testable now.
2. **Officer load-balancing scope:** SRS 15.7 says assignment "considers
   current open-complaint load to balance workload" but doesn't say
   whether that's ward-scoped too. **Decided:** department only, not
   ward — matches the Business Rules' literal wording, which names load
   as the balancing factor, not ward.
3. **Manual reassignment endpoint shape:** SRS 15.7 lists "manual
   reassignment by Admin or Department Head" as a feature; the existing
   generic `PATCH .../status` only changes status, not department/
   officer independent of a status change. **Decided:** a new dedicated
   `PATCH /api/v1/complaints/{id}/assign` endpoint.
4. **SLA-breach escalation scope:** SRS 15.7 Features mentions
   "escalation routing on SLA breach" with concrete per-severity timings
   in SRS 14.3 — this implies a background scheduler, a different kind
   of capability than routing itself, and no scheduler infrastructure
   existed in this backend before this phase. **Decided:** in scope now
   — build a real `@Scheduled` sweep (`EscalationSchedulerService`) that
   checks SLA breach per severity and annotates `is_escalated`/
   `escalated_at`.
5. **Budget approval-threshold gate timing:** Phase 10 deliberately left
   the SRS 15.9 gate unenforced, reasoning that Department Assignment
   didn't exist yet. **Decided:** implement it now that Department
   Assignment exists — block `ASSIGNED -> IN_PROGRESS` when the budget
   estimate exceeds a configurable threshold until Department Head
   approval is recorded.

### Phase 11 — Every category falls back to "General Triage" until an Admin creates a real routing rule

A direct consequence of decision 1 above: `routing_rules` still has zero
seeded rows after this phase (rule creation is now *possible*, via the
new endpoint, but nothing calls it out of the box — no seed data was
added, since a routing rule needs a real `created_by` user and inventing
one would mean fabricating Admin policy decisions the SRS doesn't
specify). Every complaint therefore continues to route to the
configurable fallback department (`app.department-assignment.fallback-
department-name`, default "General Triage", V15's own seed row) until an
Admin actually exercises `POST /api/v1/admin/routing-rules` for each
category. This is the same "the mechanism is correct, the configuration
is empty" pattern Phase 9/10 already established for AI thresholds — not
a bug, a documented starting state.

### Phase 11 — Escalation SLA hours are severity-keyed, not `routing_rules.sla_hours` (category-keyed)

`RoutingRule.slaHours` exists and is per-category (V14, Phase 2) — its
own column intent, per that migration's header comment, was "SLA time
before escalation". But SRS 14.3's literal escalation business rule is
per-severity (24h Critical / 72h High / 7 days Medium / 14 days Low), not
per-category. This is a genuine pre-existing tension in the schema: V14
was designed in Phase 2, before this phase's exact escalation rule was
worked out in SRS 14.3. Rather than silently pick one field over the
other, this is recorded explicitly: `EscalationSchedulerService` reads
new severity-keyed config (`app.escalation.sla-hours.*`, defaults
24/72/168/336 matching SRS 14.3's literal numbers) and does NOT read
`routing_rules.sla_hours` at all for escalation purposes.
`routing_rules.sla_hours` remains persisted and readable (via the new
`GET /api/v1/admin/routing-rules`) but currently has no consumer — a
future phase reconciling the two fields (e.g. deciding `routing_rules
.sla_hours` should instead represent something else, like a routing
freshness window) should treat this note as the starting point, not
re-discover the tension from scratch.

### Phase 11 — Officer load-balancing loops per-officer rather than one aggregate query

`DepartmentAssignmentService.selectOfficer` calls
`ComplaintRepository.countByAssignedOfficer_UserIdAndStatusIn` once per
eligible officer in a department, rather than a single `GROUP BY`
aggregate query returning all counts at once. Consistent with
ARCHITECTURE.md Section 7's stated preference for the simplest thing that
satisfies the SRS, given this project's department sizes (a handful of
officers per department in the seed data) — flagged as a known scaling
limit in PROJECT_PROGRESS.md rather than pre-optimized for a load this
project has no evidence of needing to handle.

### Phase 11 — Budget approval-threshold gate is now enforced

Supersedes the Phase 10 entry below ("Budget approval-threshold gate is
not enforced this phase"). `app.budget.approval-threshold-inr` (default
50,000 INR) is a real, enforced, configurable value — not a placeholder
in the sense of being fake, but not backed by a Settings-table Admin UI
either (that's Phase 14/15's eventual job); it's environment-variable-
configurable only this phase, same pattern as `complaint.reopen-grace-
period-days` (Phase 6) and every other undocumented-in-SRS numeric
default already in this project. No SRS text gives a concrete INR figure
for this threshold; the chosen default was picked as a round number
comfortably inside Phase 10's own global budget-guardrail ceiling
(500,000 INR) so a meaningful subset of real estimates would actually
exercise the gate, rather than either always or never triggering it.

### Phase 11 — `DepartmentAssignmentService` does not depend on `ComplaintService`

`ComplaintService` depends on `DepartmentAssignmentService` (calls
`assignAndApply` from both `verify()` and, indirectly via
`AiClassificationService`, the auto-verify path). Making the dependency
bidirectional would create a circular Spring bean dependency.
`DepartmentAssignmentService` therefore writes its own `StatusHistory`
row directly via `StatusHistoryRepository` rather than calling
`ComplaintService`'s package-visible `recordHistory` helper — the one
piece of genuine logic duplication this phase introduces, kept
deliberately small (a single `StatusHistory.builder()...save()` call) and
noted here so a future refactor doesn't "fix" it by re-introducing the
cycle.

### Phase 7 — No trained model ships; every classify call is honest about it

Per `ARCHITECTURE.md` Section 5's explicit instruction, this phase built
the real model-loading architecture (`app/services/yolo_service.py`) but
did not fabricate a trained model, an accuracy number, or a live
prediction. With no weights file present, `YoloService.is_available()` is
`false` in every environment, and the pipeline reports
`model_available: false`, `confidence: 0`,
`routing_reason: "MODEL_UNAVAILABLE"`, `requires_manual_review: true`
rather than inventing a detection. See `ai-service/models/README.md` for
the placement/versioning convention a real model must follow to be picked
up with no code change.

### Phase 7 — `image_base64` alternate input, beyond the SRS 20.3 literal contract

SRS 20.3's literal `/api/v1/ai/classify` request body is `{ image_url }`
only. This phase also accepts an optional `image_base64` field (exactly
one of the two is required — enforced both in the Pydantic model and in
`app/core/image_fetch.py`). Necessary because, as Section 5 now notes,
nothing in this project issues a fetchable image URL yet — without this,
the endpoint could not be exercised at all, even manually, until Phase 8
exists. This is a Phase-7-only testing convenience, not a contract change
Phase 8 is obligated to keep using once real backend-issued URLs exist.

### Phase 7 — This service's error envelope intentionally does not match the Java backend's

Section 2's Spring Boot `ErrorResponse` shape
(`{timestamp, status, error, message, path, details}`) was locked in
Phase 3 for that codebase's `GlobalExceptionHandler` specifically. SRS
20.6 ("Common API Conventions") gives its own, different literal envelope
— `{error_code, message, details}` — and since `ai-service` is a separate
Python component, not a Spring Boot controller, this phase followed SRS
20.6's literal wording for it rather than reconciling the two shapes.
Phase 8 will need to translate between them when it maps an `ai-service`
error onto whatever it returns to Flutter — flagged here rather than
silently assumed to be a non-issue.

### Phase 7 — TOO_DARK is a flag, not a rejection

SRS 21.3 lists `Too Dark` as one of the OpenCV quality-flag values but
only names `Blurry`/`Too Small` in its reject-at-intake fallback sentence.
This phase therefore rejects (422) on `BLURRY`/`TOO_SMALL` only;
`TOO_DARK` is surfaced in the response (`image_quality_flag`) but does not
block inference — treated the same way SRS 21.3's own "poor quality flag
reduces the confidence ceiling" note implies a quality flag should
generally behave. If this reading is wrong, it's a one-line addition to
`preprocessing.py`'s `_REJECTING_FLAGS` set.

### Phase 7 — Gemini agreement/disagreement confidence adjustment magnitudes are placeholders

SRS 21.2 names the *direction* of Gemini's effect on confidence
("adjusts... upward on agreement, downward on disagreement") but not a
magnitude. This phase used `+8` / `-25` (`app/services/pipeline.py`,
`_GEMINI_AGREEMENT_BOOST`/`_GEMINI_DISAGREEMENT_PENALTY`) as documented,
easily-revisited placeholders — there's no real Gemini/YOLOv11 output data
in this project yet to tune against. Whoever first runs this against a
real trained model and a real `GEMINI_API_KEY` should revisit these two
constants, not treat them as final.

### Phase 7 — OCR "engine unavailable" and "no text found" are indistinguishable in the API response

SRS 21.4's fallback logic only distinguishes "text found" vs. not; this
phase's `ocr_service.py` returns `None` identically whether `pytesseract`/
the `tesseract` binary is missing (near-certain in this workspace) or the
image genuinely has no text. The distinction is logged internally
(`logger.info`) for operator visibility but not surfaced in
`ClassifyResponse` — matches the SRS's literal external contract, which
doesn't ask for a third value.

### Phase 8 — Resolved the `image_url`/`image_base64` open item: backend always sends `image_base64`

Phase 7 flagged this as a real open item (`ARCHITECTURE.md` Section 9,
`PHASE_HANDOFF.md` Phase 7 note 5). Phase 8 resolves it by having
`StorageService` grow a `load(storageKey)` method: `AiClassificationService`
reads the citizen's stored photo bytes straight off disk and sends them as
`image_base64`, never `image_url`. The alternative — growing
`LocalStorageService` into something that can issue a fetchable, ai-service-
reachable URL for a file that was never meant to be internet-facing — was
rejected as a bigger, riskier change to a component explicitly documented
as an AWS S3 stub, for a capability (`image_url`) that has no consumer
requirement here beyond satisfying SRS 20.3's literal contract shape. The
real `image_url` code path stays implemented and correct on the ai-service
side (Phase 7, unmodified) — this decision only concerns which of the two
options the *backend* chooses to use, and it's a decision, not a
half-measure: Phase 9/10 should continue calling ai-service with
`image_base64` too, for consistency, unless a real S3 integration
(currently unscheduled) later makes `image_url` the better choice for
everyone at once.

### Phase 8 — An ai-service failure must never become a citizen-facing error

`AiClassificationService.classifyAndRoute` catches every
`AiServiceCallException` (unreachable, timeout, any HTTP error status, or
`app.ai-service.enabled=false`) and always returns a normal response with
the complaint left exactly where Phase 6 already leaves it: parked at
`AI_PROCESSING`, visible only via the existing manual verify endpoint. This
was a deliberate choice over the alternative of surfacing a 502/503-style
error to the citizen: `POST /api/v1/complaints` succeeding is a citizen-
facing SLA this project cares about (SRS 15.1), and an internal AI
microservice being temporarily down is not the citizen's problem to see.
Every failure is still recorded — `AI_CLASSIFICATION_FAILED` audit entries
— so it's operationally visible, just not part of the API response
contract. This is the translation Phase 7's note 6 flagged as Phase 8's
job: ai-service's `{error_code, message, details}` envelope is read and
logged internally, but never re-emitted in the backend's own
`{timestamp, status, error, message, path, details}` shape, because it
never reaches `GlobalExceptionHandler` at all.

### Phase 8 — A `requires_manual_review: true` result does not write a category onto the complaint

`ClassifyResponse.category` is always present, even when
`requires_manual_review` is `true` (e.g. below the confidence threshold).
Phase 8 does not copy that category onto `Complaint.category` in that case
— the complaint keeps whatever it already had (`GENERAL`, the Phase 6
placeholder) until either a later auto-approved AI call or the existing
manual Verification Team override (which requires the staff member to
supply a category explicitly) confirms one. Rationale: writing an
unconfirmed, below-threshold AI opinion into the complaint's authoritative
record would blur the line SRS 15.4's own routing rule draws between
"confident enough to trust automatically" and "needs a human" — the low-
confidence category is still fully visible to that human, just in the
`Prediction.raw_model_output` audit trail (persisted regardless of routing
outcome), not pre-filled into the field they're being asked to confirm.

### Phase 8 — RestTemplate, not WebClient, for the ai-service call

This project's locked stack (`ARCHITECTURE.md` Section 7) already excludes
reactive/event infrastructure without a demonstrated need. The backend
makes exactly one outbound, synchronous, blocking HTTP call
(`POST /api/v1/ai/classify`) — `spring-boot-starter-web`'s auto-configured
`RestTemplateBuilder` covers this with zero new Maven dependencies.
Reaching for `spring-webflux`/`WebClient` for a single blocking call would
be exactly the overengineering the architecture asks this project to
avoid; revisit only if a second, genuinely concurrent outbound integration
appears (e.g. Phase 9/10 calling multiple ai-service endpoints in
parallel) and even then only with a demonstrated need, not by default.

### Phase 8 — Known tradeoff: the ai-service call happens inside a `@Transactional` method

`AiClassificationService.classifyAndRoute` is `@Transactional`, so the
outbound HTTP call to ai-service happens while a DB connection is held
open (for up to `read-timeout-ms`, default 15s). This matches every other
method in `ComplaintService`/`AiClassificationService` (all of Phase 6 is
written the same way — one method, one transaction, no post-commit hooks
anywhere in this codebase) and is an acceptable tradeoff at this project's
synchronous, queue-free scale (Kafka/RabbitMQ/Kubernetes are explicitly
excluded by the locked architecture "unless a demonstrated need is
documented" — this alone isn't that). Flagged honestly rather than hidden:
a future phase could decouple this (e.g. `@Async`, or a post-commit
`TransactionSynchronization` hook so `POST /api/v1/complaints` returns 201
before the AI call even starts) if connection-pool exhaustion under load
ever becomes a real, measured problem — not a preemptive rewrite today.

### Phase 8 — Two pre-existing issues fixed opportunistically

While editing files this phase already needed to touch for the reasons
above, two unrelated pre-existing issues were found and fixed rather than
left silently broken:

1. `application.yml` had **two top-level `app:` keys** (one holding just
   `name: ${APP_NAME:...}` above the `server:` block, a second holding
   `jwt`/`bootstrap`/`storage`/`geo`/`complaint` further down). YAML keeps
   only the *last* occurrence of a duplicate top-level key — confirmed by
   parsing the original file with `yaml.safe_load` and observing `app.name`
   was absent from the result — so `APP_NAME` was silently unbound the
   entire time, with no startup error. Merged into the single existing
   `app:` block that Phase 8 was already extending with `ai-service:`.
2. Root `.env.example`'s `AI_SERVICE_PORT` said `8000`; ai-service's own
   authoritative default (`ai-service/app/config.py:
   Settings.ai_service_port`, `ai-service/.env.example`, and its own
   README's `uvicorn ... --port 8001` command) is `8001`. Corrected, and
   the new `AI_SERVICE_BASE_URL` the backend actually reads defaults to
   `:8001` to match. `AI_API_BASE_URL` (the unused Flutter-section entry,
   still `:8000`) was deliberately left alone — Flutter is out of scope
   this phase, and that value has no runtime consumer yet — but flagged
   with a comment so a future Flutter phase doesn't copy it forward
   uncritically.


### Phase 9 — Candidates travel in the `/duplicate-check` request body, not looked up by ai-service

SRS 20.3's literal `/duplicate-check` request body is `{complaint_id,
image_embedding, latitude, longitude}` — nothing to carry "existing open
complaints in the same ward" (15.6 Inputs), which is the whole set this
service is supposed to compare against. ARCHITECTURE.md Section 2.3
(locked Phase 1) is explicit that ai-service "is stateless with respect
to business data ... does not itself own the complaint record, MySQL" —
so, unlike a stateful service, it cannot query for candidates itself.

Resolution: the backend (`DuplicateDetectionService`, which does own the
complaint record) builds the candidate pool — same ward, an "open" status
set, within the configured time window, capped to a max count — and sends
each candidate's image + location + `created_at` alongside the new
submission in the request body. This is exactly the same shape of
decision Phase 7/8 already made for `image_url`/`image_base64` (a
documented, backend-facing addition layered on top of the literal SRS
contract, not a deviation from the locked architecture) — see
`ai-service/app/schemas/duplicate_check.py`'s module docstring for the
full reasoning written at the point of decision.

### Phase 9 — Perceptual hashing (dHash), not a learned embedding model, for image similarity

SRS 15.6 Features literally offers a choice: "image similarity comparison
(perceptual hashing / embedding similarity)". A learned embedding model
has the identical problem YOLOv11 classification already has and remains
honest about (ARCHITECTURE.md Section 5): no trained weights, no training
pipeline, ships with this repo, and none should be fabricated. A
difference hash (dHash) needs no weights, no training data, and reuses
the OpenCV dependency already present since Phase 7 — a real, standard,
non-fabricated technique, not a placeholder. `app/services/
duplicate_service.py`'s module docstring records the full reasoning.
Tradeoff, stated plainly: dHash will correctly catch near-identical
photos of the same spot but can miss two genuinely different-looking
photos of the same underlying issue (different angle, lighting, zoom) —
an accuracy ceiling a learned embedding model might not have. Logged as a
known limitation in PROJECT_PROGRESS.md, not hidden.

### Phase 9 — The candidate pool's "open" status set is a documented interpretation

SRS 15.6 Inputs says "existing open complaints in the same ward" without
enumerating exactly which `ComplaintStatus` values count as "open." This
phase defines that set as SUBMITTED, AI_PROCESSING, VERIFIED, ASSIGNED,
IN_PROGRESS — excluding RESOLVED/CLOSED (the underlying issue is
understood to already be fixed, so a new report of the same spot is a
fresh issue, not a duplicate of a resolved one) and REJECTED/DUPLICATE
(terminal, not active issues needing dedup). This is the only reading
that keeps the corroboration-count business purpose (avoiding redundant
*active* tickets) coherent, but it is an interpretation, not something
the SRS states explicitly — recorded here rather than left implicit. See
`DuplicateDetectionService`'s class Javadoc for the same reasoning
written at the point of decision.

### Phase 9 — Duplicate-check outcome takes routing priority over classify's own verdict

SRS 15.6's flow diagram places the Duplicate Detection Module's decision
point after the Verified/Manual-Queue split but before severity
prediction/assignment — read literally, a duplicate can be found
regardless of how the classification step went. This phase implements
that literally: a confirmed duplicate (AUTO_MERGE, ≥80% similarity within
50m/30 days) always wins over classify's own `requires_manual_review`
verdict and moves the complaint straight to `DUPLICATE` — even a
high-confidence, auto-approvable classification is discarded in favor of
the merge, since a merged complaint is never independently categorized. A
borderline match (MANUAL_REVIEW, 60-80%) also overrides classify's
verdict, but in the opposite direction: it parks the complaint for a
human regardless of how confident classify was, on the reasoning that a
plausible duplicate deserves a second pair of eyes before anything gets
auto-verified. Only when duplicate-checking finds nothing (or couldn't
run at all — unresolved ward, or the call itself failed) does classify's
own verdict decide auto-verify vs. manual review, exactly as Phase 8
already did. See `AiClassificationService.routeAfterChecks`'s Javadoc for
the full priority order written at the point of decision.

### Phase 9 — `AiDuplicateCandidate.createdAt` is sent as a plain ISO-8601 string, not a typed timestamp

`AiServiceClient`'s shared `ObjectMapper` (Phase 8) is deliberately
minimal — snake_case naming strategy plus `FAIL_ON_UNKNOWN_PROPERTIES:
false`, with no JSR-310 (`java.time`) module registered, since no prior
DTO needed one. Rather than adding that dependency/registration for one
new field, `AiDuplicateCandidate.createdAt` is typed `String`, populated
via `LocalDateTime.toString()` (already ISO-8601, e.g.
`2026-08-01T10:15:30`). This backend's own `LocalDateTime` columns carry
no timezone information anyway, so nothing is lost. On the ai-service
side, pydantic's `datetime` field parses a naive ISO string directly, and
`duplicate_service.py`'s `score_candidate` explicitly treats a
timezone-naive timestamp as UTC (`if candidate_created_at.tzinfo is None:
... replace(tzinfo=timezone.utc)`) — both sides make and honor the same
assumption, recorded on both ends rather than left as an implicit
coincidence.

### Phase 10 — Severity/priority/budget prediction runs at the VERIFIED transition, not inside classify-routing

SRS 14.1/14.2's own workflow diagram places "Severity/Priority/Budget
Prediction" after the "Verified or Manual Verification Queue" split and
the Duplicate Check decision point, immediately before Department
Assignment. This phase reads that literally: `PriorityBudgetPredictionService
.predictAndApply` is called at the exact moment a complaint reaches
`VERIFIED` — from both `AiClassificationService.applyAutoVerification`
(the fully-automated path) and `ComplaintService.verify`'s `VERIFIED`
branch (the manual Verification Team override, Phase 6). It deliberately
does NOT run inside `routeAfterChecks`'s `MANUAL_REVIEW`/manual-review-park
branches, where a complaint sits at `AI_PROCESSING` with a placeholder
`GENERAL` category — there's nothing meaningful to score until a real
category is locked in. A confirmed `AUTO_MERGE` duplicate never reaches
`VERIFIED` at all (see Phase 9's routing decision above), so it never
reaches this module either — consistent with the diagram's own "Duplicate
Found (merged, end)" branch never rejoining the prediction step.

### Phase 10 — Staff-supplied severity always wins over the AI's own prediction

SRS 15.8 Features lists "manual override by Officer/Department Head with
justification" as part of the Priority Prediction Module itself.
`VerificationDecisionRequest.severity` (Phase 6) is exactly that override
— it can arrive before this module ever runs, via the manual-verify path.
This phase's `PriorityBudgetPredictionService.predictAndApply` takes a
`staffOverrideSeverity` parameter; when non-null, `complaints.severity` is
set from it directly (`ComplaintService.verify` applies it immediately,
unconditionally, even before this module runs — so a staff override
survives even if ai-service is entirely down), and priority-predict's own
severity output is recorded only in `predictions.raw_model_output` for
audit, never applied to the column. The numeric `priority_score` and the
subsequent budget estimate are still computed against the final
(possibly-overridden) severity either way — an override changes which
severity feeds the pipeline, not whether the pipeline runs at all.

### Phase 13 — `reassign()`/`approveBudget()` had no department-scope check at all since Phase 11 — CLOSED

The Phase 13 brief's own "IMPORTANT SECURITY" callout ("A Department Head
must never access another department's complaints, officers or
performance data through direct API calls") turned out to describe a
real, pre-existing gap rather than a hypothetical one: `PATCH
.../complaints/{id}/assign` and `PATCH .../complaints/{id}/approve-budget`
have both been reachable by any DEPARTMENT_HEAD since Phase 11 with *no*
department check whatsoever — the only gate was `@PreAuthorize`'s role
check, which doesn't know which department the caller belongs to. In
practice this meant a DEPARTMENT_HEAD could reassign or budget-approve
any complaint system-wide, and reassign it into any target department,
purely by supplying a `complaintId` (and, for reassignment, a
`departmentId`) that wasn't their own — exactly the "guess/increment an
ID" bypass Phase 12's entry below already closed for the read path
(`GET /complaints`/`GET /complaints/{id}`) but never touched for these
two write actions, since neither existed as a Flutter-reachable action
until Phase 13 built the Department Head screens that call them.

Fixed by adding `requireCanView(staff, complaint)` — the exact same
helper the read path already used — at the top of both methods (a no-op
for ADMIN/SUPER_ADMIN, matching their unrestricted read-path behavior),
plus one additional check on `reassign()` specific to write access: a
DEPARTMENT_HEAD's `request.departmentId()` must equal their own
department, or the call is rejected with 403 before any other validation
runs. This was chosen over introducing a *new* scoping helper because
`requireCanView`'s existing DEPARTMENT_HEAD branch already expresses
exactly the right rule ("this complaint must already be in your
department") — reassignment only adds the extra "...and you can't move
it to a different department" constraint on top, which plain complaint
*viewing* has no equivalent for.

One deliberate edge-case consequence, worth flagging explicitly: a
complaint that reached `VERIFIED` with `department = null` (the
`DepartmentAssignmentService`-failed-and-was-caught case that method's
own class Javadoc describes) can now only be claimed via `reassign()` by
ADMIN/SUPER_ADMIN, never by any DEPARTMENT_HEAD — `requireCanView`
treats a null `complaint.department` as out of scope for that role. This
is judged correct rather than a regression: an unrouted complaint isn't
yet "their" department's queue to reach into, and Admin already has the
unrestricted authority to route it manually.

### Phase 13 — SLA-compliance-% and average-resolution-time formulas are this project's own choice, not verbatim SRS text

SRS 16.2 names an "SLA compliance chart" and 15.10/24.4 name "SLA
compliance"/"average resolution time" as dashboard KPIs, but the document
never gives a formula for either — the same kind of genuine specification
gap this project has resolved-and-documented (rather than silently
guessed at) since Phase 1's tech-stack conflict. Resolved as follows,
both computed by `DepartmentPerformanceService`:

- **SLA compliance %** = `(totalComplaints - escalatedComplaints) /
  totalComplaints * 100`, where `escalatedComplaints` reuses the existing
  `complaints.is_escalated` flag `EscalationSchedulerService` already
  sets on an SLA breach (Phase 11) — a complaint that was never escalated
  is treated as SLA-compliant. A department with zero complaints reports
  100% (nothing has ever breached), not a division-by-zero error. This
  was chosen over introducing a new per-complaint "SLA compliant/
  breached" column because `is_escalated` already *is* exactly that
  signal for every complaint currently in the system — adding a second,
  parallel column would just be two names for the same fact.
- **Average resolution time** (department-wide, and per-officer for the
  workload table) = the mean of `updatedAt - createdAt` across every
  RESOLVED/CLOSED complaint in scope, computed in Java rather than a SQL
  `AVG(TIMESTAMPDIFF(...))` aggregate — see the KNOWN LIMITATIONS entry
  in PROJECT_PROGRESS.md for the scale trade-off this accepts. A
  department/officer with zero resolved complaints reports `null`
  ("N/A" in the CSV export and Flutter UI), not `0` — reporting a false
  "instant resolution" signal for "nothing has ever been resolved yet"
  would be actively misleading to a Department Head reading the KPI
  tile.

### Phase 13 — Department Performance View is deliberately narrower than the full SRS 15.10/16.3 Government Dashboard

SRS 16.2's "Department Performance View" (Department Head permission)
and SRS 15.10/16.3's "Government Dashboard" (broader: heatmaps,
cross-department comparison charts, jurisdiction-wide rollups, named for
Admin/Super Admin) are two different screens in the SRS, and
ARCHITECTURE.md Section 8's Phase → Component map assigns the latter to
Phase 16 (Dashboards) — a separate `GET /api/v1/dashboard/summary`
endpoint (Table 25/26) that this phase deliberately does not build.
Phase 13 only implements the narrower, single-department view: new
`GET /departments/{id}/performance` (+ `/officers`, `/performance/export`)
endpoints, distinct from and not a subset call into whatever Phase 16
eventually builds for `/dashboard/summary`. A future Phase 16 should
reuse `DepartmentPerformanceResponse`'s shape/formulas for its own
per-department rollups rather than redefining them, so the two screens'
numbers stay consistent (see PROJECT_PROGRESS.md's "INTEGRATION
REQUIREMENTS FOR NEXT PHASE").

### Phase 13 — Report export is CSV-only this phase; no file-save/share affordance

SRS 21 says reports "can be exported as PDF or CSV." This phase
implements CSV only, via a hand-rolled string writer
(`DepartmentPerformanceService.exportPerformanceCsv`) — no new backend
dependency, consistent with this class's existing hand-rolled-JSON
precedent (`ComplaintService.escapeJson`) and this environment's
no-outbound-network constraint (no Maven Central access to pull in a PDF/
CSV library even if one were wanted). PDF generation is judged out of
scope for Phase 13 specifically — it's a formatting/rendering concern
that belongs with a dedicated Reports Module, which ARCHITECTURE.md's
Phase → Component map does not currently assign a phase number to;
guessing at PDF layout now would be scope creep into an unplanned future
phase rather than implementing something the map actually calls for.

On the Flutter side, the exported CSV is copied to the clipboard
(`Clipboard.setData`, `dart:services` — no new pub dependency) rather
than saved to or shared as a file, because no `path_provider`/
`share_plus` package is available in this environment (no outbound
network to add one). Documented as a Phase 13 KNOWN LIMITATION in
PROJECT_PROGRESS.md, not a silent simplification — a future phase with
network access to add such a dependency could upgrade this to a real
file-save/share flow without changing the backend endpoint at all.

### Phase 13 — DepartmentHeadHomeScreen kept as its own screen, not a role-branch inside OfficerHomeScreen

`OfficerHomeScreen` (Phase 12) is a single-tab shell around
`OfficerQueueScreen`, shared today by both GOVERNMENT_OFFICER and
DEPARTMENT_HEAD accounts (the queue itself is already scoped correctly
server-side for both roles). Phase 13 introduces a second, genuinely
Department-Head-only tab (Performance) and could have added it to
`OfficerHomeScreen` behind a role check instead of creating
`DepartmentHeadHomeScreen` as a new file. Chose the new file because (a)
a plain GOVERNMENT_OFFICER must never even momentarily construct the
Performance tab's widget tree — SRS 16.2 gives that screen no
GOVERNMENT_OFFICER permission at all, so there's no reason for that
account type's screen to know the Performance tab exists — and (b) the
Performance tab needs one extra `GET /users/me` call
(`DepartmentHeadHomeScreen` fetches it once in `initState`) that
`OfficerHomeScreen` has no other reason to ever make; keeping them
separate means a future change to one role's home shell can't
accidentally regress the other's.

### Phase 13 — Fixed a pre-existing broken import in `home_router.dart` while editing that file for its own routing change

`features/home_router.dart`'s `session.dart` import was
`'../auth/session.dart'` since Phase 12 — relative to that file's own
directory (`lib/features/`), this resolves to a non-existent
`lib/features/auth/session.dart` (or `lib/auth/session.dart`, depending
on interpretation), not the real file at `lib/core/auth/session.dart`.
This would have failed to resolve the first time anyone ran `flutter
analyze`/`flutter build` against this file — a defect this project's
manual validation approach (brace/paren-balance + package-path +
import-resolution cross-checks) is specifically designed to catch, and
did catch this phase once the same check was run against every touched
Dart file (see PROJECT_PROGRESS.md's TESTS entry). Corrected in place to
`'../core/auth/session.dart'` rather than left for a future phase to
trip over, since Phase 13 already needed to touch this exact file/line
for its own DEPARTMENT_HEAD routing addition. No functional behavior
depended on the broken path (no Flutter build has ever actually been run
against this codebase in this environment — see the "no live JDK/Maven/
MySQL/ai-service process" constraint restated in every phase's KNOWN
LIMITATIONS), so this fix carries no risk of changing already-verified
behavior; it only removes a defect that had never yet been exercised.

### Phase 12 — Officer/Department Head queue scoping is now a hard server-side default

Phase 11's own entry immediately below ("Staff visibility on GET
/complaints is not department-scoped") left this open for "whichever
phase builds the actual officer/department-head-facing queue UI" — that
is this phase. `ComplaintRepository.findForOfficerOrDepartment` and
`ComplaintService.list`/`requireCanView` now force a GOVERNMENT_OFFICER
to their own assigned complaints and a DEPARTMENT_HEAD to their own
department, with no caller-suppliable override for either role (an
explicit `departmentId` query param is silently ignored for those two
roles rather than honored or rejected — it can't be used to widen scope,
and would be redundant for a GOVERNMENT_OFFICER since it's implied by
their own department anyway). VERIFICATION_TEAM/ADMIN/SUPER_ADMIN/
MAINTENANCE_TEAM keep the unrestricted Phase 6 behavior described in that
Phase 11 entry — none of those roles has an SRS-documented "own queue"
concept, so narrowing them wasn't in scope. The single-record
`GET /complaints/{id}` enforces the identical restriction (via the same
`requireCanView` helper the list path already used for citizens), closing
what would otherwise be a bypass: an officer could not be shown another
officer's complaint in their list but could previously still fetch it
directly by ID.

### Phase 12 — Classification override is a new action, not an extension of the Phase 6 manual-verify override

SRS 15.8 Features ("manual override by Officer/Department Head with
justification") and SRS 16.2's "Override Classification" button describe
a different action from the Phase 6 `verify()` override
(`PROJECT_INTEGRATION.md`'s own "Manual Verification Team override: role
scope" entry below): `verify()` is the Verification Team's one-time
stand-in for the (never-built) AI confidence-threshold auto-decision,
legal only while a complaint sits at AI_PROCESSING, and its actor set is
VERIFICATION_TEAM/ADMIN/SUPER_ADMIN. The Phase 12 action is a field
officer or department head's post-assignment correction once they've
actually looked at the issue, legal from VERIFIED through IN_PROGRESS,
actor set GOVERNMENT_OFFICER/DEPARTMENT_HEAD/ADMIN/SUPER_ADMIN. Rather
than overload `verify()` with a second legal time window and a second
actor set (which would make its own Javadoc/tests harder to reason
about), this phase added a genuinely separate
`PATCH /complaints/{id}/classification` endpoint and
`ComplaintService.overrideClassification` method. Both require at least
one of category/severity to be non-null (a request with both null isn't
a real override) and a >=10-character `reason`, matching SRS 16.2's
mandatory-justification field. The change is recorded as an AuditLog
entry (`COMPLAINT_CLASSIFICATION_OVERRIDDEN`), not a StatusHistory row —
status itself never changes — the same non-status-change precedent
`EscalationSchedulerService` established in Phase 11 (see that phase's
entries below).

**Deliberately NOT done this phase:** re-running
`PriorityBudgetPredictionService.predictAndApply` when severity changes
via this endpoint. The existing budget/priority estimate from initial
verification is left untouched. This is a real, documented scope
boundary (see PROJECT_PROGRESS.md's KNOWN LIMITATIONS), not an oversight
— if a future phase's SRS reading determines an override should trigger
re-prediction, that call is a one-line addition to
`overrideClassification` using the exact same service Phase 10 already
built.

### Phase 12 — Internal notes and manual escalation are AuditLog entries, not new tables

SRS 16.2 Complaint Detail (Officer View) describes an "Add Internal
Note" button and an "Escalate" button. Neither corresponds to an existing
table in the locked schema (V1–V18), and adding a new table for either —
especially for a single free-text field with an author and a timestamp —
would duplicate what `audit_logs` already provides. This phase reuses
Phase 11's own "ESCALATED is an annotation, not a status" precedent (see
that phase's entry below) one step further: both actions write an
AuditLog row (`COMPLAINT_INTERNAL_NOTE_ADDED` /
`COMPLAINT_ESCALATED_MANUAL`) rather than mutating `complaints.status` or
introducing a new persistence concept. `AuditLogRepository` gained one
derived finder method
(`findByEntityTypeAndEntityIdAndActionTypeOrderByCreatedAtAsc`) to read
internal notes back out for a given complaint — adding a query method to
an `AppendOnlyRepository` doesn't violate its append-only contract, since
only the inherited CRUD surface (no delete) is narrowed, not Spring
Data's query-derivation mechanism.

Internal notes are staff-only by construction:
`ComplaintResponse.internalNotes` is only populated when
`ComplaintService.toResponse` is called with `includeInternalNotes=true`,
which every citizen-facing call site (`create`, `reopen`,
`AiClassificationService`'s auto-verification flow) deliberately never
passes — they keep using the original no-arg `toResponse(complaint)`
overload, now a thin wrapper defaulting to `false`/`List.of()`.

### Phase 12 — PATCH .../status converted to multipart/form-data

Phase 6's `StatusUpdateRequest` Javadoc flagged as a KNOWN LIMITATION
that the SRS's optional `after_photo` field on this endpoint wasn't
implemented, since a JSON `@RequestBody` can't carry a file part. This
phase converts the endpoint's `consumes` to `multipart/form-data`
(`newStatus`/`note` as request params, `afterPhoto` as an optional
request part) rather than adding a second, parallel JSON-plus-separate-
upload endpoint. This is a breaking shape change to an existing endpoint,
which would normally warrant caution, but is safe here: no client had
started calling the JSON version of this endpoint before this phase — the
Flutter citizen app never calls `PATCH .../status` at all, and the
Officer-facing Flutter screens that do call it are new this same phase.
`ComplaintService.updateStatus` now also enforces SRS Table 8's
conditional validation inline (note >=10 chars mandatory for
RESOLVED/REJECTED targets; afterPhoto mandatory for RESOLVED, stored via
the same `StorageService`/`Image` pattern complaint creation already
uses, with `imageType=AFTER`).

### Phase 10 — No location-sensitivity data source exists; every flag is honestly false

SRS 15.8 Features lists "location-sensitivity weighting (proximity to
schools, hospitals, high-traffic roads)" as a scoring input. No POI/
geometry data source exists anywhere in this project's schema —
`wards.boundary_geojson` (V1) has been unused since it was added, and no
schools/hospitals/traffic layer was ever modelled by any prior phase. Per
this project's own build-the-real-capability-don't-fabricate-the-input
precedent (Phase 7's untrained YOLOv11 weights, Phase 9's dHash instead of
a learned embedding), this phase implements the real one-level-severity-
bump weighting logic in `ai-service/app/services/priority_service.py`, but
the backend always sends `AiLocationSensitivityFlags.NONE_AVAILABLE`
(`{near_school: false, near_hospital: false, high_traffic_road: false}`).
A future phase that adds a real POI/ward-boundary data source can wire it
in with no ai-service-side change at all — this is a backend-side data
gap, not an algorithm gap.

### Phase 10 — Budget guardrails are a single global floor/ceiling, not genuinely per-category

SRS 15.9 Validation Rules literally asks for "configurable minimum/maximum
guardrails per category." No Admin-configuration mechanism exists yet for
that granularity (the Admin Module, SRS 15.11, is Phase 14 and not built).
This phase implements a single global guardrail
(`budget_guardrail_min_inr`/`budget_guardrail_max_inr`,
`ai-service/.env`) applied uniformly across every category as a sanity
clamp, while the actual per-category *variation* comes from
`budget_service.py`'s static `BASE_COST_TABLE`. This is a documented,
acknowledged gap against the SRS's literal per-category wording, not a
silent narrowing — see that module's docstring for the full reasoning.

### Phase 10 — Every budget estimate is honestly PRELIMINARY; no historical cost/duration data exists

SRS 21.8's Confidence Score is "derived from the volume and recency of
historical data available for the specific category/severity/ward
combination," with a documented Fallback Logic for the cold-start case:
"cold-start categories return a 'preliminary estimate — low confidence'
flag using citywide averages." This project has never seeded or built any
historical department cost/duration dataset (the Reports module, SRS
15.9's own documented source, is Phase 16 and not built), so every request
this phase is a cold-start request by definition — `budget_service.py`
hardcodes `confidence: "PRELIMINARY"` rather than leaving an unreachable
`STANDARD`/`HIGH` code path in place that no real data source could ever
legitimately trigger. The static `BASE_COST_TABLE` values themselves are
illustrative citywide-average planning figures, not sourced from any real
municipal dataset (SRS Out of Scope: "not a financial commitment").

### Phase 10 — Severity/priority/budget prediction is rules-based, not a trained ML model

SRS 21.6's Fallback Logic ("insufficient historical data defaults to a
conservative static severity table") and 21.8's cold-start fallback (see
above) both describe exactly the situation this entire project is in for
every request this phase — there has never been a historical resolution
dataset to train against. Rather than fabricate an ML component with no
real training data behind it, `priority_service.py`/`budget_service.py`
implement the SRS's own documented fallback as the complete, honest
implementation: a static per-category severity table with a safety-hazard
override and corroboration-count bump (SRS 15.8 Business Rules, both
implemented literally), and a static per-category/severity cost/duration
table (SRS 15.9). Same "build the real always-available capability, don't
fabricate the other half" choice Phase 9 made for duplicate detection
(dHash instead of a learned embedding).

### Phase 10 — Budget approval-threshold gate is not enforced this phase

SRS 15.9 Business Rules: "estimates above a configurable threshold require
Department Head approval before the complaint can move to In Progress."
Not implemented — Department Assignment (Phase 11) doesn't exist yet, so a
complaint cannot structurally reach `IN_PROGRESS` through this backend
regardless of any budget estimate (see PROJECT_INTEGRATION.md Section 2's
`/status` endpoint note and ARCHITECTURE.md Section 4). `budget.approved_by`
is persisted as `null` for every row this phase; a future phase building
the approval gate can populate it without any change to the estimate
itself.

**SUPERSEDED Phase 11:** this gate is now implemented and enforced — see
"Phase 11 — Budget approval-threshold gate is now enforced" below. Kept
here for history, not deleted.

### Phase 10 — The Prediction row is updated in place, not duplicated, when one already exists

`predictions` is an intentionally non-unique, append-only-per-attempt
table (V8 migration's own header comment: "the most recent row for a
complaint is authoritative"). Rather than insert a second row purely for
the severity/priority/budget update, `PriorityBudgetPredictionService`
looks up the most recent existing row for the complaint (new
`PredictionRepository.findFirstByComplaint_ComplaintIdOrderByCreatedAtDescPredictionIdDesc`)
and updates its `predicted_severity`/`priority_score` columns in place,
merging this phase's detail into that same row's `raw_model_output` JSON
under a new `priority_predict_and_budget_predict` key (leaving Phase 8/9's
own `classify`/`duplicate_check` keys untouched). Only when no row exists
at all (the manual-verify path when ai-service was entirely
disabled/unreachable at submission time, so classify never ran) is a new
row created — with a documented `aiConfidence: 0.00` sentinel, since that
column has no real classification-confidence meaning for a row that never
went through classify.

### Phase 7 — `preprocessed_image_reference` is always null this phase

SRS 15.4 lists "pre-processed image reference" among the module's outputs.
This phase's normalized image exists only in memory during a single
request — there's no storage integration for `ai-service` yet (Section 5
notes the same open `image_url` gap this connects to). The field exists in
`ClassifyResponse` and is documented as always-null pending whichever
phase adds real persistence for it, rather than silently dropped from the
schema or faked with a placeholder path.

### Phase 6 — ESCALATED/REOPENED are annotations, not status values

V6's own header comment explicitly left this choice open for Phase 6 to
resolve. The SRS's behavioral description only makes sense if neither
value is ever persisted into `complaints.status`: "Escalated: an
annotation (not a terminal state) ... the underlying status continues to
progress normally once escalated"; reopen "resets status to In Progress
with a reopen flag". `ComplaintStateMachine` therefore never transitions
a complaint into `ComplaintStatus.ESCALATED` or `.REOPENED` — those two
enum/CHECK values remain valid (harmless unused allowance) but
`is_escalated`/`is_reopened` + their timestamp columns (already present
since Phase 2/3, for exactly this) are what actually get set. See
`ComplaintStateMachine`'s class Javadoc for the full reasoning.

### Phase 6 — POST /complaints returns AI_PROCESSING, not SUBMITTED

SRS Table 23's own example response for `POST /api/v1/complaints` shows
`status=Submitted`. This phase's implementation persists/returns
`AI_PROCESSING` instead — a deliberate, documented deviation, not an
oversight. Reasoning: `SUBMITTED` is described elsewhere in the SRS as
functionally a zero-duration transit state ("queued for AI processing...
within seconds of submission"), and Table 10 documents the Verification
Team as acting on complaints while still nominally "AI Processing," which
only makes sense if that's where a freshly created complaint actually
sits. The `SUBMITTED` row is still fully written to `status_history` (so
the transition is never lost or skipped in the audit trail), just not the
resting state of a fresh `create` call. If this reading is wrong, it's a
one-line fix in `ComplaintService.create` (drop the second, system-
initiated transition call) — flagged here rather than silently decided
either way.

### Phase 6 — Manual Verification Team override: role scope

SRS 13.6 permits the Government Officer role to act as Verification Team
"in smaller deployments." This phase's `PATCH .../verify` endpoint is
restricted to `VERIFICATION_TEAM`, `ADMIN`, `SUPER_ADMIN` only —
`GOVERNMENT_OFFICER` is deliberately excluded. Reasoning: allowing it
would require deciding what `ActorType` value an Officer-acting-as-
Verification-Team's `status_history` rows should carry (`OFFICER`, which
would misrepresent the actual capacity they're acting in, or
`VERIFICATION_TEAM`, which would misrepresent their actual `Role`) — an
ambiguity not worth introducing for a permission the SRS itself frames as
an optional smaller-deployment allowance, not a requirement. If a real
deployment needs this, it's a one-line addition to
`ComplaintStateMachine`'s `ACTOR_ROLES` map for `AI_PROCESSING`, plus a
resolved answer to the `ActorType` question above.

### Phase 6 — Local-disk storage stub, not S3

This phase's explicit instruction required a storage abstraction with a
local stub, not real AWS S3 ("DO NOT require real AWS credentials"). See
Section 5 for the full contract and what's genuinely not yet fulfilled
(pre-signed URL issuance / photo viewing).

### Phase 6 — Flutter scaffolded this phase; login screen is intentionally minimal

`flutter/` had zero code through Phase 5 (documented deferral). Phase 6
needed *some* way to obtain a JWT to make the two required Complaint
Module screens actually usable/testable, so a minimal login screen
(mobile-or-email + password only) was built alongside them. This is NOT
the Authentication Module's real Flutter screen set — no registration,
OTP verification, MFA, or password-reset screens exist — consistent with
this phase's explicit "Do not start unrelated frontend modules"
instruction. `AuthApi`/`ApiClient`/`TokenStorage` are structured so a
future phase can build out the real Authentication Module screens
without reworking this plumbing; only `LoginScreen` itself gets replaced.
A citizen test account must be created via the backend API directly
(Postman) until a registration screen exists.

### Phase 6 — Staff visibility on GET /complaints is not department-scoped

Every non-citizen role currently sees every complaint via
`GET /api/v1/complaints[/{id}]`. This is a documented, temporary
widening — meaningful department-based scoping requires
`complaints.department_id` to actually be set by something, which is
Department Assignment (Phase 11)'s job, not built yet. Revisit
`ComplaintRepository.findForStaff`/`ComplaintService.requireCanView` once
that phase lands.

**PARTIALLY SUPERSEDED Phase 11:** `complaints.department_id` is now
actually set by `DepartmentAssignmentService` once a complaint reaches
`ASSIGNED` — the precondition this note was waiting on now exists. The
`departmentId` query filter on `GET /api/v1/complaints` already lets
staff self-filter to their own department if they choose to. What
remains NOT done: `findForStaff`/`requireCanView` themselves were not
changed to *default*-restrict a GOVERNMENT_OFFICER/DEPARTMENT_HEAD to
only their own department without an explicit `departmentId` query param
— that narrowing wasn't one of the five decisions confirmed for this
phase (see PROJECT_PROGRESS.md "INTEGRATION REQUIREMENTS FOR NEXT
PHASE"). Still worth revisiting whichever phase builds the actual
officer/department-head-facing queue UI (Phase 12/13).

### Phase 5 — Citizen Module scope: what's implemented vs. deferred, and why

SRS 15.1 (Citizen Module) lists six features. This phase implements only
the two that don't depend on the Complaint entity:
- **Registration and profile management** — registration was Phase 4;
  profile management (`PUT /api/v1/users/me`) is this phase.
- **Reputation score display** — already exposed on `UserProfileResponse`
  since Phase 4; nothing new needed.

The remaining four are explicitly NOT implemented this phase, because
each requires the `Complaint` entity, which ARCHITECTURE.md Section 8's
phase map assigns to Phase 6 (Complaint Module, state machine
implementation) — building any of them now would mean silently starting
Phase 6 inside a Phase 5 session, which this project's own hard-stop-at-
phase-boundaries convention rules out:
- Photo-based complaint submission
- Complaint history and status tracking
- Community heatmap view
- Feedback/rating submission on resolved complaints

The anti-spam rule ("max 10 complaints per rolling 24-hour period") and
the "must verify mobile/email before first complaint" rule are both
enforcement points that live at complaint-creation time — also deferred
to Phase 6, not built as a bare unattached check here.

### Phase 5 — Profile update excludes mobileNumber/email

`PUT /api/v1/users/me` only accepts `fullName` and `wardId`. Both mobile
number and email are login identifiers with their own verification state
(`mobile_verified_at` / `email_verified_at`, Phase 4). Changing either
self-service would need a dedicated re-verification (OTP) flow — SRS 15.2
doesn't specify one, and `OtpPurpose` (V16) has no
`MOBILE_CHANGE`/`EMAIL_CHANGE` value to key it off. Rather than build that
flow speculatively or silently allow an unverified identifier swap, both
fields are left immutable via self-service this phase. If citizens need
this, it should be raised explicitly as its own item (new `OtpPurpose`
values need a new Flyway migration to widen `otp_verifications.purpose`'s
CHECK constraint, not an edit to the already-shipped V16).

### Phase 5 — Ward lookup is authenticated, not public

`GET /api/v1/wards` requires a JWT, consistent with SRS 18 ("All
endpoints except registration, login, and OTP verification require a
valid JWT") and `SecurityConfig`'s documented fail-closed default. This
means a not-yet-registered citizen can't fetch the ward list from the app
before creating an account; `RegisterRequest.wardId` (Phase 4) already
accepts a raw ID without server-side validation against this endpoint, so
this doesn't block registration itself, but it does mean any future
Flutter registration screen needs another source for its ward dropdown
(e.g. a bundled/seeded list, or the SRS's own stakeholder confirming
ward-lookup should be public after all). Flagged here rather than
silently making it public against SRS 18's explicit wording.

### Phase 4 — JWT access-token expiry corrected to match the SRS

`.env.example`'s `JWT_ACCESS_TOKEN_EXPIRY_MINUTES` default was `15`
(Phase 1 placeholder, written before the real SRS existed). SRS 15.2/27.1
both state "JWT access tokens expire after 30 minutes" explicitly.
Corrected to `30` in `.env.example` and `application.yml` this phase — no
other phase depended on the old value (no auth code existed before now).

### Phase 4 — Refresh tokens are opaque + persisted, not JWTs

SRS 27.5 requires single-use rotation and whole-family revocation on
reuse, which is only enforceable server-side. Access tokens remain
stateless JWTs (SRS 27.1); refresh tokens are random opaque strings,
SHA-256-hashed before storage in `refresh_tokens` (V17). See that
migration's header and `RefreshTokenService` for the full design.

### Phase 4 — Rate limiting is in-memory, not distributed

ARCHITECTURE.md Section 7 explicitly excludes Redis for this
academic-scale project. `RateLimitingFilter` is therefore a per-JVM
fixed-window counter (SRS 27.3: 100 req/min per authenticated user) —
correct only for a single backend instance. If a future phase needs
horizontal scaling, this needs a shared store; flagged here rather than
silently building around it.

### Phase 3 — Tech stack conflict between the locked architecture and the SRS/BRD/FRS — RESOLVED at Phase 3 start

- **Resolution:** this phase's explicit instruction ("Phase 3 — Spring Boot
  Foundation") is treated as the team/stakeholder confirmation Phase 2
  flagged as required before proceeding: MySQL + Java Spring Boot + Flutter
  (Phase 1's locked architecture) stands; the SRS/BRD/FRS Appendix A.2
  stack (PostgreSQL + FastAPI + React.js) is not adopted. Phase 3 was built
  entirely on Spring Boot/MySQL on that basis.
- Full prior decision record (kept for history, not superseded): the
  SRS/BRD/FRS document supplied in Phase 2 (Appendix A.2, "Technology Stack
  Summary") specified PostgreSQL, FastAPI (Python), and React.js —
  conflicting with the stack locked in Phase 1 (`ARCHITECTURE.md`). Phase 2
  proceeded on MySQL as the minimum-risk default and flagged the conflict
  as blocking before Phase 3. See `database/docs/data-dictionary.md`
  decision note 1 and `ARCHITECTURE.md` Section 9 for full detail.
- **If this reading of the Phase 3 instruction is wrong** — i.e. if the
  intent was actually to switch to PostgreSQL/FastAPI/React.js — that needs
  to be raised explicitly before Phase 4, since Phase 3 has now
  substantially deepened the Spring Boot/MySQL investment (13 JPA entities,
  Flyway wiring, Maven build).
- **CONFIRMED at Phase 4 start:** this session's explicit instruction set
  "LOCKED TECHNOLOGY STACK: Spring Boot + MySQL + Flutter" — the reading
  above was correct. Phase 4 proceeded on Spring Boot Security + JWT + RBAC
  with no further ambiguity; this question is now closed.

### Phase 14 — Admin User & Role Management: the exact role-privilege matrix

SRS 15.11's Business Rules line ("only Super Administrator may create or
modify Admin accounts") is read literally as covering every mutating
action `AdminUserService` exposes, not just account creation: role
change to/from `ADMIN`, status change, password-reset trigger, and
session revocation on an existing `ADMIN` account all additionally
require the acting user to already be `SUPER_ADMIN`. An ordinary `ADMIN`
actor may manage `GOVERNMENT_OFFICER`/`DEPARTMENT_HEAD`/
`VERIFICATION_TEAM`/`MAINTENANCE_TEAM` accounts only. `CITIZEN` and
`SUPER_ADMIN` are never manageable through this endpoint at all —
`CITIZEN` is self-registration only (`AuthService#register`); `SUPER_ADMIN`
remains bootstrap-only (`SuperAdminBootstrap`, Phase 4) — there is
deliberately no application-layer path to mint a second Super Admin, even
for an existing Super Admin. See `AdminUserService`'s class Javadoc for
the full matrix; enforced once in `requireManageableRole`, not duplicated
per-action.

### Phase 14 — Admin-created staff accounts get a one-time temporary password, not an emailed/texted one

The Notification Module (Phase 15) is still a logging-only stub — there
is no real SMS/email delivery channel to hand a generated password to.
Rather than fabricate a fake "invite sent" response, `POST
/api/v1/admin/users` generates a random password meeting the same
strength policy as citizen self-registration (`RegisterRequest`'s
pattern) and returns it exactly once, directly, in
`AdminCreateUserResponse` — never persisted in plaintext (only its
BCrypt hash is stored), never retrievable again, and never logged. The
Flutter "Add User" flow shows it in a one-time dialog with an explicit
"cannot be retrieved again" warning. Documented as a known limitation
tied to the Notification Module's own documented stub status, not a
silent simplification — revisit once Phase 15 has a real delivery
channel (at which point the response could switch to "credentials sent
to registered mobile number" instead).

### Phase 14 — Admin "Reset Password" reuses the existing self-service OTP flow rather than a new Admin-only path

`AuthService.adminTriggerPasswordReset` issues the exact same
`PASSWORD_RESET` OTP `forgotPassword` issues for a self-service request —
an Admin can only *trigger* it; the target user still must complete the
reset themselves via `POST /auth/reset-password` with the OTP they
receive. This keeps a single password-reset code path (no parallel
Admin-sets-a-new-password-directly flow to keep in sync with the OTP
one) and guarantees an Admin can never see or choose a user's actual
password, even transiently.

### Phase 14 — Admin-configurable platform thresholds override existing `@Value` defaults; they don't replace them

SRS 15.15 ("Admin-level default thresholds: AI confidence, duplicate
similarity, SLA timers") and SRS 15.6 ("all \[SLA] thresholds
configurable by Admin") both name behavior that, before this phase, was
fixed at deploy time via `application.yml` `@Value` injection
(`RoutingRuleService`'s `DEFAULT_AI_CONFIDENCE_THRESHOLD`/
`DEFAULT_DUPLICATE_SIMILARITY_THRESHOLD` constants,
`EscalationSchedulerService`'s `app.escalation.sla-hours.*` fields,
`ComplaintService`'s `app.budget.approval-threshold-inr` field — the last
two each carrying their own "Phase 14" TODO comment already). Rather
than replace those fields with a settings-table read (which would make
every environment's tuned defaults vanish silently on upgrade), the new
`PlatformSettingsService.getOverride(key)` returns `Optional<String>`:
each consumer keeps its original `@Value` field as the fallback default
and only changes behavior once an Admin has actually written a
`PLATFORM`-scoped `settings` row. Zero behavior change for every
existing Phase 1-13 deployment until an Admin opts in. The Admin Settings
screen's "recommended default" column shows the literal number each
field's Javadoc documents (e.g. 85.00, 24), which may not exactly match
a given deployment's real `@Value`-configured default — documented
explicitly in `PlatformSettingsService`'s own Javadoc rather than
conflating "recommended" with "what's actually in effect right now" (a
per-deployment "effective value" endpoint was considered and rejected as
unnecessary complexity for this phase).

### Phase 14 — The SRS's duplicate-merge-vs-review threshold conflict rule doesn't apply to this schema

SRS 15.15's Exceptions line reads: "attempts to set conflicting
thresholds (e.g., duplicate-merge threshold higher than duplicate-review
threshold) are blocked." This codebase has a single
`duplicate_similarity_threshold` field per `RoutingRule` row (V14, Phase
2's schema) — there is no second "review" threshold anywhere in the
schema to conflict-check the "merge" one against; the Duplicate
Detection Module (Phase 9) never split the concept in two. Rather than
invent a second threshold field the rest of the system has no use for
just to satisfy this one Exception line, the rule is treated as
inapplicable to this schema and is not implemented —
`PlatformSettingKey`'s own class Javadoc documents this explicitly under
"SCOPE NOTE" so it reads as a deliberate, reasoned omission rather than
an overlooked requirement.

### Phase 14 — Routing rule "deactivate" is the closest available analog to the Admin screen's "Revert to Default" action, not a literal implementation of it

SRS 16.3's Routing screen lists "Save Configuration, Revert to Default,
View Change History" as its three actions. `RoutingRuleRepository`'s
supersede-by-new-row design (Phase 11, per V14's own "keep a history of
rule changes over time" comment) has no concept of a separate "default
row" a rule could revert to — the "current" rule for a category is
simply whichever `is_active` row has the latest `effective_from`. Rather
than invent a synthetic "default" concept the schema doesn't have,
`RoutingRuleService#deactivate` (new this phase) retires a specific row;
since `findCurrentActiveRule` always resolves to the latest still-active
row, deactivating the current one causes the next-latest active row for
that category (if any) to become current automatically — functionally a
rollback to the prior configuration, which is the closest honest reading
of "Revert to Default" this design supports. Documented in
`RoutingRuleService`'s class Javadoc rather than silently reinterpreted
as something it isn't.

### Phase 14 — What's explicitly out of scope, and why

Several SRS 15.11/15.15/16.3 items that could plausibly be read as
"Admin & Settings Module" work were deliberately left out this phase,
each for a documented reason rather than an oversight:

1. **Government/Admin Dashboard** (SRS 15.10/16.3's KPI tiles/heatmaps/
   cross-department trend charts for Admin/Super Admin) — explicitly
   Phase 16 (Dashboards) per `ARCHITECTURE.md`'s Phase -> Component map;
   this phase's Settings/Users/Routing/Audit-Log screens are
   configuration and administration surfaces, not the analytics
   dashboard.
2. **Ward-boundary geometry editing** (SRS 16.3's "ward-boundary map
   editor") — no geometry/GIS engine exists anywhere in this codebase
   (same gap flagged since `V2__create_departments.sql`'s own header
   comment); nothing to build this on top of without introducing a whole
   new capability far outside "Admin & Settings" scope.
3. **Department creation/editing** — departments remain Phase 2's seeded,
   fixed set (same decision Phase 11 already made and left unchanged);
   no SRS requirement forces this open this phase, and opening it would
   touch `RoutingRule`/`User.department`/`Complaint.department` foreign-
   key referential-integrity concerns not otherwise in scope.
4. **Platform-wide announcements / a maintenance-mode flag** (SRS 15.11
   Features) — the Notification Module (Phase 15) is still a
   logging-only stub; a maintenance-mode flag with no real delivery or
   enforcement effect would be a fake stub, not a working feature, so it
   is deferred alongside the Notification Module itself rather than
   half-built now.
5. **Citizen notification preferences / officer availability settings**
   (part of SRS 15.15's broader "Settings" concept) — same Notification-
   Module dependency as item 4; a preference with nothing yet consuming
   it isn't a real feature.
6. **Reports Module** (SRS 15.12) — a distinct SRS section from Admin
   15.11, not addressed this phase; belongs with a future Reports-
   specific phase.

### Phase 15 — IN_APP and EMAIL are mandatory for major alerts, regardless of the `emailEnabled` preference

SRS 15.13's own text conflicts with itself across two subsections. The
Exceptions clause says: "citizens who opt out of SMS/push still receive
mandatory in-app and email notifications for legal/status-transparency
purposes" — read literally, email cannot be turned off for a
citizen-facing status-change alert. But the 20.5 API contract table
still lists `email_enabled` as one of three toggles on
`PUT /notifications/preferences`, implying it IS a real switch.

Resolved by treating the Exceptions clause as the binding business rule
for the three trigger methods this phase adds
(`notifyComplaintStatusChanged`/`notifyOfficerAssigned`/
`notifySlaBreachWarning`): each unconditionally dispatches IN_APP and
EMAIL, and only conditionally dispatches SMS based on the stored
preference. `emailEnabled` is still a real, persisted
`NotificationPreferenceKey` and is returned/updatable through the API
exactly as the contract table describes — it simply isn't consulted by
these three specific trigger methods. A future notification type that
isn't a legally-mandated status alert (e.g. a weekly digest, if one is
ever added) would be the natural place to actually read this preference.

The Flutter Settings screen makes this explicit rather than letting the
switch appear to silently do nothing: the Email row's subtitle reads
"Status updates are always emailed, regardless of this setting."

### Phase 15 — PUSH is a real, persisted preference that is never actually dispatched

SRS 20.5's preferences endpoint names three channels: `sms_enabled`,
`push_enabled`, `email_enabled`. SMS and email both got real gateway
implementations this phase (`SmsGatewayClient`/`EmailGatewayClient`).
Push did not, for a structural reason rather than a scheduling one:
sending a real push notification requires a device token (FCM
registration ID or APNs token) to send *to*, and no such column exists
anywhere in this schema — `users` (V1) has no token field, and no
migration in this project has ever added a device-registration table.

Building real push support this phase would mean: a new
`user_device_tokens` table (or column) with its own migration, a
registration endpoint the Flutter app calls on login/app-start, Flutter
FCM SDK integration (a new native dependency, platform config on both
iOS and Android, and a Firebase project this workspace has no way to
provision or test against), and then wiring `NotificationService`'s
dispatch path to call it. That is a materially larger, separate feature
than "Notifications & Personal Settings" as scoped, and no SRS 20.5
acceptance criterion requires push to actually arrive on a device this
phase — only that the preference exists and can be toggled.

Resolved by keeping `push_enabled` as a fully real, working preference
(stored, returned, updatable, and rendered as a working switch in
Flutter) while `NotificationService`'s three trigger methods simply
never call `dispatch(..., NotificationChannel.PUSH, ...)` for any
recipient — not because the preference says so, but because the
capability doesn't exist yet. `sendOnce`'s `PUSH` case throws
`NotificationDeliveryException` as a defensive guard against a future
accidental call, but that branch is provably unreached today (grep the
three trigger methods — none of them ever passes `PUSH` to `dispatch`).
This is the same "don't fabricate a working channel" discipline as
Phase 11's honest `model_available: false` for the untrained YOLOv11
wrapper — the honest answer is "not yet," documented, not silently
half-built.

### Phase 15 — `DepartmentAssignmentService.assignAndApply` needed its own explicit notification calls, separate from `ComplaintService.recordHistory`

The natural first instinct is: hook every citizen status-change alert
into one place, `ComplaintService.recordHistory` — every status
transition `ComplaintService` itself produces (verify, updateStatus,
reopen, reassign) already funnels through that single private method,
so one hook there covers all of them.

That instinct is wrong for the auto-assignment path specifically.
`DepartmentAssignmentService` (Phase 11) was deliberately built with no
dependency on `ComplaintService` at all — see this file's own Phase 11
entry, "`DepartmentAssignmentService` does not depend on
`ComplaintService`" — to avoid a circular-dependency risk between the
two services. Consequently it has its own separate, private
`recordHistory` method that writes `StatusHistory` rows independently.
A hook placed only inside `ComplaintService.recordHistory` would
therefore never fire for the single most common ASSIGNED transition in
the system: the automatic department/officer assignment that happens
right after a citizen's complaint is verified.

Resolved by giving `DepartmentAssignmentService` its own `NotificationService`
dependency (no circular-dependency risk — `NotificationService` depends
on neither `ComplaintService` nor `DepartmentAssignmentService`) and
calling `notifyComplaintStatusChanged`/`notifyOfficerAssigned` explicitly
inside `assignAndApply`, right alongside its own existing
`recordHistory`/`auditService.record` calls. This was caught only by
tracing every real call site of an ASSIGNED transition before writing
any notification-hook code, not by assuming `recordHistory`'s name meant
there was only one such method in the codebase.

### Phase 15 — The 80%-SLA-warning sweep de-duplicates via a dedicated AuditLog action type, not a new boolean column

SRS 15.13's Business Rules require officers receive "SLA-breach warnings
at 80% of SLA time elapsed" — a strictly earlier, separate signal from
`EscalationSchedulerService`'s existing 100%-elapsed breach/escalation
sweep (Phase 11), which already runs on a fixed interval and flips
`Complaint.isEscalated`. A naive new sweep re-querying the same 80%-100%
window on every interval would re-warn the same complaint every 15
minutes (the sweep's existing cadence) until it either resolves or
crosses into the 100% breach window — clearly wrong.

Two ways to prevent that: add a new boolean column (e.g.
`sla_warning_sent`) to `complaints`, or reuse the existing `AuditLog`
trail this codebase already treats as the record of "did this specific
thing already happen to this complaint." The second was chosen,
matching the exact precedent `EscalationSchedulerService` itself already
set for a structurally identical problem (see this file's Phase 11
entry — escalation write an `AuditLog` row rather than adding tracking
infrastructure elsewhere). `EscalationSchedulerService.warnForSeverity`
checks `AuditLogRepository.findByEntityTypeAndEntityIdAndActionTypeOrderByCreatedAtAsc("COMPLAINT",
id, "COMPLAINT_SLA_WARNING_SENT")` before calling
`notifySlaBreachWarning`, and writes that same action type immediately
after — no new Flyway migration needed for this feature at all.

### Phase 15 — `OFFICER_AVAILABILITY_STATUS` defaults to `AVAILABLE` for an officer who has never set it, not left null

`PersonalSettingsService.getSettings` needs to tell the Flutter client
whether to render the officer-availability control at all — it only
makes sense for a `GOVERNMENT_OFFICER` account. The obvious first
implementation returned `null` for `officerAvailabilityStatus` in two
completely different cases: (a) the caller isn't a `GOVERNMENT_OFFICER`
at all, and (b) the caller IS a `GOVERNMENT_OFFICER` but has never
touched this setting (no `settings` row exists yet for that key). Both
looked identical to the client — `null` either way — so Flutter had no
reliable signal for "show this control" versus "don't."

Caught during the Flutter integration pass (not during backend coding in
isolation) precisely because writing the actual conditional-render logic
(`if (settings.officerAvailabilityStatus != null) ...`) surfaced the
ambiguity immediately. Resolved the same way `language`/
`highContrastEnabled` already handle their own "never set" case: give
`OFFICER_AVAILABILITY_STATUS` a real default (`AVAILABLE`) for a
`GOVERNMENT_OFFICER` caller specifically, so `null` now unambiguously
means "not an officer" and any non-null value (including the default)
means "is an officer, this is their current status." A non-officer
caller still always gets `null` — the role check happens first, before
any lookup.

### Phase 15 — Notification dispatch is synchronous, on the caller's own request/scheduler thread

No message broker (RabbitMQ, Kafka, SQS, etc.) exists anywhere in this
stack, and introducing one is a significant infrastructure decision well
outside "Notifications & Personal Settings" scope. `NotificationService.dispatch`
therefore runs synchronously: a citizen's `PUT /complaints/{id}/status`
request (or the department auto-assignment path, or the escalation
scheduler's own thread) blocks for the full duration of up to 3 retry
attempts with exponential backoff before returning.

The backoff base (`app.notification.retry-backoff-base-ms`, default
200ms) was deliberately kept small rather than the multi-second backoff
a real SMS/email provider integration might reasonably use, specifically
because of this synchronous constraint — a citizen submitting a status
update should not routinely wait several seconds for a notification
side-effect to finish. In this workspace (no live SMTP/SMS credentials,
both gateways defaulting to their disabled logging-stub path — see the
PUSH/mandatory-email entries above), every dispatch succeeds on the
first attempt in practice, so the retry loop and its backoff never
actually engage during any of this phase's own validation. Moving this
to a genuinely async queue (so a slow provider can't block a citizen-
facing request at all) is documented in `PROJECT_PROGRESS.md`'s
INTEGRATION REQUIREMENTS FOR NEXT PHASE as a real follow-up, not
implemented here — no broker exists in this stack to build it on top of
without introducing one from scratch, which is a decision bigger than
this phase's scope.

### Phase 16 — Officer Dashboard and Citizen Dashboard are out of scope; the SRS 16.3 screen-permission line is treated as authoritative over SRS 15.10's general business-rule text

SRS 15.10's Business Rules say "data visibility scoped by role (Officer
sees own department/zone; Department Head sees whole department; Admin
sees whole jurisdiction; Super Admin sees all jurisdictions)" — read on
its own, this could be taken to mean a GOVERNMENT_OFFICER should also
get some dashboard view this phase. But SRS 16.3's own screen
specification for "Government Dashboard (Overview)" — the actual screen
this phase's title names — has its own, narrower Permissions line:
"Department Head, Admin, Super Admin." These are two SRS statements
about the same screen that disagree; the more specific one (naming the
exact screen and its exact permission set) was treated as authoritative
over the more general one (describing the module's visibility rules in
the abstract). GOVERNMENT_OFFICER therefore has no access to any Phase
16 endpoint.

This also settles SRS 24.2 ("Officer Dashboard": queue breakdown,
personal-vs-department performance) and 24.1 ("Citizen Dashboard")
being out of scope: this phase's own title is "Government/Admin
Dashboard & Analytics" — not "all dashboards" — and 24.2/24.1 describe
screens with genuinely different content (an individual officer's own
queue and personal performance; a citizen's own complaint history) than
the jurisdiction/department-level aggregate views 15.10/15.14/24.3/24.4
describe. Building a same-shaped-but-narrower dashboard for Officer or
Citizen this phase would be scope creep beyond what was asked for, not
a natural extension of the same screen. Deferred, named explicitly, not
silently folded into the endpoints this phase does build.

### Phase 16 — The full Reports Module (SRS 15.12) stays out of scope; CSV-only export follows Phase 13's exact precedent

SRS 15.10's Outputs line says the Government Dashboard produces
"exportable report snapshots," and SRS 15.12 separately describes a
full Reports Module: multiple report types (daily/weekly/citizen-
engagement/budget), PDF *and* CSV export, and scheduled email delivery.
ARCHITECTURE.md Section 8's Phase → Component map has no phase of its
own for "Reports" — the closest candidates (a future Notification-
adjacent or Admin-adjacent phase) don't exist yet.

Phase 13 already resolved an identical tension for the Department
Performance View (whose own Outputs line also mentions an exportable
report) by shipping CSV-only export via a hand-rolled `StringBuilder`
writer, explicitly deferring PDF and scheduled delivery. Phase 16
reuses that exact precedent rather than inventing a second, different
export convention in the same codebase: `GovernmentDashboardService.exportOverviewCsv`
follows the identical shape (plain CSV text, `text/csv` content type,
`Content-Disposition: attachment`, copied to the clipboard on the
Flutter side since no file-save/share plugin exists in this
environment — see Phase 13's own entry above for that constraint).

### Phase 16 — "Routing-rule effectiveness" (SRS 24.3) has no SRS-given formula; resolved as a per-rule SLA-compliance-% reusing Phase 13's existing formula

SRS 24.3 lists "routing-rule effectiveness" as one of the Admin
Dashboard's charts but never defines what "effectiveness" means
numerically — the same category of gap as Phase 13's own undefined SLA-
compliance-% formula (see that phase's entry above), which this project
resolved by picking `(total - escalated) / total * 100` and documenting
it rather than guessing silently.

Phase 16 extends that exact formula to a new dimension: for each active
`RoutingRule` (a category → department pairing), effectiveness is the
same SLA-compliance-% computed only over complaints matching that
pairing's category *and* department. A rule whose routed complaints
rarely breach SLA reads as "effective" by this measure. This was chosen
specifically because it's the same formula every other KPI on this
dashboard already uses (department comparison, per-department KPI
tiles) — one consistent definition of "SLA compliance" across the whole
module, rather than a routing-rule-specific metric that would need its
own separate justification.

### Phase 16 — "System health indicators" (SRS 24.3) does not mean infrastructure monitoring here; it means notification-delivery health and current SLA-breach load

SRS 24.3 lists "system health indicators" among the Admin Dashboard's
Statistics, right next to "configuration change audit summary." Read
alongside SRS Section 20 (which separately describes real
infrastructure/application metrics — CPU, memory, request latency,
error rate, queue depth — "visualized on an operations dashboard"),
these are two different things the SRS calls "health": one operational
(this project has zero infrastructure-monitoring code or dependency
anywhere, and no phase in ARCHITECTURE.md's Phase → Component map owns
building it yet), one this project actually has real data for.

Phase 16's Admin Dashboard reports the latter: the notification-
delivery failure rate over the last 30 days (Phase 15's
`Notification.deliveryStatus`, a real, already-tracked field) and the
count of complaints currently sitting in an unresolved SLA-breach state
(`Complaint.isEscalated`, Phase 11). This is a substitution, made
explicit rather than silently presented as satisfying the SRS's literal
"CPU/memory/latency" framing — a future Monitoring-owning phase (likely
alongside Phase 18's docker/ or Phase 22's deployment/ work) is the
right place to build the infrastructure-level version, and should
extend `AdminDashboardSummaryResponse` rather than replace this
substitution outright, since notification-delivery health remains a
genuinely useful signal on its own.

### Phase 16 — ADMIN and SUPER_ADMIN are treated identically; this schema has no multi-jurisdiction data model

SRS 15.10's Business Rules distinguish "Admin sees whole jurisdiction"
from "Super Admin sees all jurisdictions" — implying a data model with
multiple jurisdictions, each containing its own departments/wards, that
an Admin is confined to one of and a Super Admin can see across. No
`Jurisdiction` entity, no jurisdiction foreign key on `Department` or
`Ward` or `User`, exists anywhere in this schema (V1-V13's Flyway
migrations) — this project has modeled a single jurisdiction throughout
since Phase 1, and no phase between Phase 1 and Phase 15 introduced one.

Every Phase 16 endpoint therefore treats `ADMIN` and `SUPER_ADMIN`
identically: both may request a jurisdiction-wide view
(`departmentId = null`) or scope to any specific department, with no
further distinction. This is the same "N/A, not a bug" resolution this
project has already applied to every other single-jurisdiction
assumption baked into the schema since Phase 1 — introducing a real
`Jurisdiction` entity to make the SRS's distinction meaningful is a
schema-level change well outside a dashboard phase's scope, and would
need its own dedicated phase with its own Flyway migration.

### Phase 16 — The nightly analytics cache is in-process, not backed by an external cache store

SRS 15.14's Business Rules ("trend predictions are refreshed on a
configurable schedule... rather than computed on every dashboard load,
to protect performance") and SRS 15.10's Exceptions ("if real-time
aggregation service is degraded, the dashboard falls back to the last
successfully cached aggregate with a visible 'data as of [timestamp]'
notice") together describe a real caching requirement, not just a
performance nicety — this phase implements both literally rather than
computing live on every request and treating the caching language as
optional flavor text.

No Redis, Memcached, or any other external cache store exists anywhere
in this stack, and ARCHITECTURE.md Section 7 explicitly lists
introducing a cache layer as a non-goal "unless a real, demonstrated
technical requirement forces it." `AnalyticsCacheService` therefore
caches in-process — a `ConcurrentHashMap` keyed by department ID plus a
single `AtomicReference` for the jurisdiction-wide snapshot, both
living inside a singleton Spring bean. This does not survive a JVM
restart and would not be correctly shared across multiple horizontally-
scaled backend instances in a real production deployment — acceptable
for this project's current single-instance scope, and documented as a
KNOWN LIMITATION in `PROJECT_PROGRESS.md` rather than silently assumed
away. A future phase introducing real horizontal scaling should replace
this with a shared cache store at that point, not before it's actually
needed.

### Phase 16 — Heatmap and category-trend aggregation are computed in Java, not via a SQL `GROUP BY`

Both the ward-density heatmap (SRS 15.10/16.3/24.4) and the category-
trend chart (SRS 15.10/24.4) need grouped counts — by ward, and by
(category, day) respectively. The natural SQL approach is a `GROUP BY`
query, potentially using a date-truncation function (e.g. HQL's
`FUNCTION('DATE', ...)`) for the day-bucketing. This project's "manual
validation only" constraint (no live JDK/Maven/MySQL in this
environment — see every phase's own TESTS section) makes an untested
JPQL `FUNCTION(...)` call or a constructor-expression `GROUP BY` query
a real correctness risk: there is no way to actually run it and confirm
it compiles and returns the right shape before shipping it.

Phase 13's `DepartmentPerformanceService` already made and documented
an equivalent tradeoff for average-resolution-time (loading matching
complaints into memory and computing the mean in Java, rather than a
SQL `AVG`), explicitly calling it acceptable at this project's current/
expected civic-complaint scale. Phase 16 extends that same tradeoff to
two more aggregations: `ComplaintRepository#findForAnalytics` returns
the candidate row set (department-scoped if requested, date-ranged),
and `AnalyticsAggregationService#aggregateHeatmap`/`#aggregateCategoryTrend`
group those rows in plain Java using `LinkedHashMap`-based accumulators
— simple, easy to hand-verify without a compiler, and consistent with
this project's established "simplest thing that satisfies the SRS"
convention. A future phase migrating to a real SQL aggregation (or a
dedicated materialized-view read model) once real data volume actually
demands it should start with those two methods.

### Phase 17 — Scope itself had to be determined before coding; "flutter/ (full integration)" resolved as closing the Authentication Module's Flutter gap

ARCHITECTURE.md Section 8 has only ever carried a one-line placeholder
for Phase 17 ("flutter/ (full integration)"), set at Phase 1 before the
project's later per-module phase split existed — unlike every phase
since Phase 11, there was no pre-recorded concrete scope to execute.
Resolved by auditing every backend controller's endpoints against every
endpoint Flutter's `ApiClient` actually calls (see PROJECT_PROGRESS.md's
"SCOPE DETERMINATION" section for the full method). Finding: the entire
Authentication Module backend (register/verify-otp/resend-otp/mfa-verify/
forgot-password/reset-password/logout/logout-all) has existed and worked
since early phases, but Flutter's `AuthApi` was deliberately left at
Phase 6's bare minimum — with the concrete, severe consequence that no
ADMIN or SUPER_ADMIN account could sign in through the app at all
(`AuthApi.login()` threw a bare `StateError` on the `mfaRequired`
response SRS 27.1 requires for exactly those two roles), and citizens
had no self-registration path through the app. Resolved as this phase's
scope: a Flutter-only, zero-backend-change integration of a backend
capability that already fully exists — matching the component map's
"flutter/ (full integration)" label literally (only `flutter/` needed
touching) and in spirit (it makes the Flutter client a complete,
integrated consumer of a module it was previously only a partial one
of). See PROJECT_PROGRESS.md for the full audit method and the two
adjacent items (Community Heatmap/Rate/Appeal, and budget-approval
visibility) explicitly found and explicitly left out — both would have
required new backend surface area, which this phase's scope doesn't
include.

### Phase 17 — `AuthApi.login()`'s return type changed from `Future<void>` to `Future<LoginResult>`

The only behavior-changing (not just doc-comment-updating) edit to
pre-existing Phase 6 code this phase made. Before this phase,
`AuthApi.login()` threw a `StateError` the instant the backend responded
with `mfaRequired: true` — meaning the one call site (`login_screen.dart`)
could never actually reach a branch that handled it; MFA accounts simply
could not log in. Fixed by changing the return type to a new
`LoginResult` class (`mfaToken`/`mfaRequired`) so the caller can branch
instead of catching a crash. Confirmed via `grep` that `login_screen.dart`
was the only call site anywhere in the app before making this change,
and updated it in the same phase. No other Phase 6-16 `AuthApi` method
signature changed — `logout()` kept its exact `Future<void> logout()`
signature (see the next entry) specifically so its four existing call
sites needed no changes at all.

### Phase 17 — `AuthApi.logout()` now calls `POST /auth/logout` server-side, but stays best-effort so it can never fail to log the user out locally

Phase 6-16's `logout()` was a bare `_tokens.clear()` — it never told the
backend a session had ended, so a stolen/leaked refresh token would have
remained valid server-side indefinitely after a user "logged out" in the
app. Phase 17 adds the real `POST /auth/logout` call (revokes that
refresh token's family per `LogoutRequest`'s own Javadoc) but wraps it in
`try/catch` and clears local storage unconditionally afterward — a
network failure or an already-expired refresh token at the exact moment
of logout must never leave the user stuck looking like they're still
signed in. The same best-effort pattern is used for the new
`logoutAll()` (`POST /auth/logout-all`, SRS 27.5) added this phase and
wired into `PersonalSettingsScreen`'s new "Account" section.

### Phase 17 — Ward dropdown at registration: a real SRS-vs-security-model conflict, resolved by submitting `wardId: null`

SRS Screen 16.1 lists "Ward/Area (dropdown)" as a Registration screen
field. `GET /api/v1/wards` is `.requestMatchers("/api/v1/wards/**").authenticated()`
in `SecurityConfig` (a Phase 5 decision, not revisited this phase) — a
citizen filling out the registration form has no JWT yet, so this
endpoint is structurally unreachable from that screen. This is a genuine
conflict between SRS 16.1's screen-field list and SRS 18's own security
model, not something Phase 17 introduced or could resolve by inventing
new backend surface (out of this phase's Flutter-only scope).
`RegisterRequest.wardId` is already a nullable `Long` (no `@NotNull`) —
itself evidence a prior phase already anticipated registration
proceeding without one. Resolved by having `RegisterScreen` submit
`wardId: null`. No screen anywhere in this app (including
`PersonalSettingsScreen`) currently lets a citizen set their ward after
registration either — left as an explicit, documented gap (see
PROJECT_PROGRESS.md's INTEGRATION REQUIREMENTS FOR NEXT PHASE for the
two concrete resolution options a future phase should choose between).

### Phase 17 — Community Heatmap, Rate Resolution, and Appeal Rejection (SRS 16.1) stay out of scope; no backend endpoint exists for any of them

Found during this phase's endpoint-audit but deliberately not built:
grepping every controller and DTO in the backend turns up no aggregate/
anonymized complaint-density endpoint, no resolution-rating endpoint, and
no rejection-appeal endpoint anywhere in this codebase. All three would
require designing new backend request/response contracts and (for
ratings) new persistence — genuine net-new backend work, not just
Flutter catching up to an existing contract the way this phase's
Authentication Module work was. Left open for a future phase with its
own backend design work; not assumed to be Phase 17's job merely because
it's Citizen-Module-adjacent (this phase's own component-map assignment
is `flutter/` only).

### Phase 17 — Budget approval action stays unwired; `ComplaintResponse` has no budget fields for any screen to show

`PATCH /complaints/{id}/approve-budget` (Phase 11) is a real, working
endpoint, but `ComplaintResponse.java` — the only shape any Flutter
screen ever receives back from any Complaint API — carries no budget
amount, no approval threshold, and no pending-approval flag at all
(confirmed by reading the full DTO). A Flutter "Approve Budget" button
wired to this endpoint with nothing to show the staff member *why* an
approval is being requested, or what amount they're approving, would be
a decorative integration rather than a real one. Left open for a future
phase, which should add the relevant budget fields to `ComplaintResponse`
(or a small nested `BudgetSummaryResponse`) as its own first backend
step before any Flutter screen attempts to surface the action — see
PROJECT_PROGRESS.md's INTEGRATION REQUIREMENTS FOR NEXT PHASE.

### Phase 17 — MFA verification screen has no "Resend OTP" action

`ResendOtpRequest` needs a `mobileNumber`, but `MfaVerificationScreen`
only ever receives the short-lived `mfaToken` returned by `/auth/login`
(by design — that endpoint's own Javadoc: "no tokens issued yet").
`LoginScreen`'s `identifier` field cannot substitute, since it explicitly
accepts either a mobile number or an email (`LoginRequest`'s own doc
comment) and there is no reliable way to tell which was used after the
fact. Rather than guess/parse `identifier` against the mobile-number
regex to conditionally show a resend button, this was left as a known,
narrow limitation: an Admin/Super Admin whose OTP expires simply
re-submits login, which issues a fresh `mfaToken` and OTP from scratch.

### Phase 18 — Root `.env.example` and `.gitignore` did not exist before this phase

Both files are referenced by name throughout the codebase — `application.yml`'s
own header comment ("see root .env.example"), `flutter/lib/core/api/api_config.dart`'s
doc comment ("Matches the root .env.example convention"), `README.md`'s
repository-layout diagram (both files listed since Phase 1), and this
section's own Phase 4 and Phase 8 entries above, which describe edits
made *to* a root `.env.example` (correcting `JWT_ACCESS_TOKEN_EXPIRY_MINUTES`
and `AI_SERVICE_PORT` respectively) — but a full recursive search of the
Phase 17 baseline ZIP, performed before writing any Docker file this
phase, found neither file anywhere in the repository. Docker Compose
cannot function without a root env file for its `${VAR}` interpolation
and per-service `env_file:` injection, so recreating both was necessary
groundwork for this phase's actual `docker/` deliverable, not a
speculative addition.

Both were rebuilt from the current, live, authoritative source for every
value — `application.yml`'s own `${VAR:default}` syntax for every backend
setting, `ai-service/app/config.py`/`ai-service/.env.example` for every
AI-service setting — rather than attempting to reconstruct the old
file's exact prior content from this section's own historical prose,
which describes edits to a file whose resulting content can no longer be
verified. One naming inconsistency from that history was deliberately
NOT carried forward: the Phase 8 entry above mentions an old
`AI_API_BASE_URL` Flutter-section entry, but `api_config.dart`'s own
current doc comment (still accurate, unchanged since Phase 6) names the
convention `API_BASE_URL` — the live source file wins over unverifiable
old prose. Whether this is a packaging gap in some intervening phase's
ZIP or an actual regression cannot be determined from this project's own
records; flagged here rather than silently reconstructed as if nothing
had happened.

### Phase 18 — Docker build context is the repo root, not `docker/` or `backend/` alone

`backend/pom.xml`'s `copy-flyway-migrations` execution (added Phase 6,
with a comment explicitly anticipating "Phase 18's Docker image") reads
`${project.basedir}/../database/migrations` at build time — a path that
only resolves if `database/` is present as a sibling of `backend/` in
whatever build environment runs `mvn package`. `docker/Dockerfile.backend`'s
build stage therefore `COPY`s `database/migrations` and `backend/` into
the image maintaining that exact same relative layout, and
`docker/docker-compose.yml` sets `build.context: ..` (the repo root, one
level up from `docker/`) for both the backend and ai-service images —
not `context: .` from inside `docker/`, and not `context: ../backend`
either, since the latter would break the migrations path the same way
building from `docker/` would. `docker/.dockerignore` does not exist as
a separate file for this reason — a single root `.dockerignore` covers
both images' shared build context, explicitly keeping `database/migrations/`
while excluding `database/docs|scripts|seed/` (not needed inside either
image).

### Phase 18 — `ai-service` has no `depends_on` in `docker/docker-compose.yml`

Considered and deliberately rejected: gating `ai-service`'s startup on
`mysql` becoming healthy, for symmetry with `backend`'s own such gate.
Rejected because `ai-service` has no database of its own —
`ai-service/app/config.py` has no `DB_*`/database-connection setting at
all, confirmed by reading the full `Settings` class — so it has nothing
to wait for. Adding a dependency that doesn't reflect a real runtime
requirement would misrepresent this service's actual architecture (see
Section 2.3/ARCHITECTURE.md's "Statelessness, reaffirmed Phase 9" note)
for the sake of surface-level symmetry with `backend`'s row above it.

### Phase 18 — `AI_SERVICE_ENV`/`SPRING_PROFILES_ACTIVE` default to `local`/`dev`, not `production`/`prod`, in the root `.env.example`

`app/main.py`'s own startup handler refuses to boot when
`AI_SERVICE_ENV != local` and `AI_SERVICE_API_KEY` is still the
placeholder value (a real, intentional Phase 7 fail-loudly guard — see
that file's `on_startup`). Defaulting the new root `.env.example` to
`production` would mean `docker compose up` fails immediately for anyone
who has not yet generated a real API key, on their very first run of
this phase's own Quick Start instructions. Set to `local`/`dev` instead,
matching `application.yml`'s own stated design philosophy ("sane
local-dev defaults so ... works out of the box") applied to this new
file for the same reason — `docker/README.md`'s Quick Start explicitly
tells the reader to switch both to `production`/`prod` (alongside real
secrets) before treating this as anything beyond a disposable local
sandbox run.

### Phase 18 — Flutter, real infrastructure monitoring, CI/CD, and AWS deployment config deliberately stay out of `docker/`'s scope

Found by this phase's own scope-check before writing any Docker file,
each rejected for a different reason rather than bundled in just because
they're adjacent:
- **Flutter** compiles to a mobile app, not a long-lived server process —
  there is nothing for a container to keep running. A future Flutter
  *web* build target would be a genuinely new deliverable (a static-file
  web server image), not something implied by containerizing the two
  server components that already exist.
- **Real infrastructure/application monitoring** (SRS Section 20:
  CPU/memory/latency/error-rate/queue-depth) would mean standing up a new
  service (Prometheus/Grafana or equivalent) and adding a new backend
  dependency (`micrometer-registry-prometheus`, not in `backend/pom.xml`)
  neither of which is "package what already exists into a container" —
  see `ARCHITECTURE.md` Section 9's updated Phase 16 entry.
- **CI/CD pipelines** (`.github/workflows/`) are Phase 21's own
  component-map assignment. A real pipeline would *use* these two
  Dockerfiles (`docker build`/`docker push` steps) but building the
  pipeline itself is a different phase's job.
- **AWS deployment configuration** (`deployment/`) is Phase 22's own
  assignment. This phase's Compose file targets a single-host
  local/pilot run, not a production AWS topology (ECS/EKS task
  definitions, an RDS endpoint replacing the `mysql` container, etc.) —
  a future Phase 22 should treat these Dockerfiles as the images it
  deploys, not rebuild them from scratch.

See `docker/README.md`'s "What isn't containerized" section for the
reader-facing version of this same reasoning.

### Phase 19 — Postman collection built from live controller/route source, not from this document's own prose

This document's Section 2 table above is a hand-maintained prose summary
of the API contract, written incrementally phase-by-phase — useful as a
narrative, but never re-verified line-by-line against the actual current
source in one pass. Rather than transcribe Section 2 directly into
`postman/JanNet_AI.postman_collection.json` (which would have propagated
any drift this document had already accumulated), every request's method,
path, `@PreAuthorize` role list, and body/response shape was built by
re-reading the live source directly: all eleven backend
`controller/*.java` files, every DTO record referenced by those
controllers (`dto/**/*.java`), `GlobalExceptionHandler.java` for error
shapes, and all four `ai-service/app/api/routes/*.py` route modules plus
their Pydantic schemas. Section 2's table was used only as a
cross-reference to confirm nothing was missed, never as the primary
source. Net result: this phase's own re-audit found Section 2 already
accurate (no drift had accumulated) — worth recording explicitly rather
than leaving future readers to wonder whether the Postman collection or
this document is more current. **They now describe the same 56 endpoints;
if a future phase changes an endpoint, both must be updated together.**

### Phase 19 — Two error envelopes, and pagination shape, made explicit for the first time in one place

Section 2 above documents the backend's `ErrorResponse` shape and
separately (further down) the AI service's distinct `{error_code,
message, details}` shape, but neither this document nor
`ARCHITECTURE.md` had previously written down, in one place, that Spring
Data's `Page<T>` JSON envelope (`content`, `totalElements`, `totalPages`,
`number`, `size`, `first`, `last`, `numberOfElements`, `empty`) is what
every `page`/`pageSize`-paginated endpoint actually returns (`GET
/complaints`, `GET /notifications`, `GET /admin/users`, `GET
/admin/audit-logs`) — SRS 20.6's own "Pagination uses page and page_size
query parameters, with total_count and has_next in the response envelope"
describes a *different*, simpler envelope shape (`total_count`/`has_next`)
than what `Page<T>`'s default Jackson serialization actually produces.
**Decided:** document what the code actually returns (Spring Data's
native envelope) as authoritative in `postman/README.md`'s Pagination
section, per this phase's "actual implemented API contracts, not
invented ones, are the source of truth" instruction — not silently
reshape every paginated controller's return type to match the SRS's
literal field names, which would be Phase 19 (documentation) quietly
doing a Phase 6/12/14/15 (implementation) phase's job. Left open for
whichever future phase next touches these controllers to decide whether
closing this SRS-vs-implementation gap is worth a breaking response-shape
change for Flutter — not assumed to be in scope here.

### Phase 19 — Scope determination: `postman/` only, no `docs/` content, no OpenAPI/Swagger changes

`ARCHITECTURE.md` Section 8 names this phase's component plainly:
`postman/`. The empty `docs/` directory sitting alongside it in the
Phase 18 baseline was inspected and left untouched — nothing in
`ARCHITECTURE.md`'s Phase → Component map assigns it to Phase 19 (or any
phase so far), so populating it would be scope creep beyond "API
documentation and Postman collection" as stated in this phase's own
brief. Likewise, `springdoc-openapi` (already a locked dependency since
Phase 1 per `ARCHITECTURE.md` Section 6, auto-generating
`/v3/api-docs`/Swagger UI from the live controllers) needed no code
change — this phase's deliverable is a hand-authored Postman collection
*for external/manual API testing and reference*, a different artifact
serving a different purpose than the auto-generated OpenAPI spec Flutter
codegens against (Section 2's own "the generated spec is the source of
truth Flutter codegens/consumes against" line, unchanged and still true).

### Phase 20 — Environment capability re-check: `pypi.org` is now reachable, so the AI service's pytest suite was actually executed for the first time

Every prior phase touching `ai-service/tests/` (7 onward) documented "NOT
VERIFIED (not executed) — no network/package-manager reach" as a
standing, unquestioned constraint. This phase re-checked that assumption
before writing any test code (this project's own "inspect before
implementing" convention) and found the network allowlist now includes
`pypi.org`/`files.pythonhosted.org` — `pip install` genuinely works here
now. **Decided:** install the lightweight dependency set
(`fastapi`, `httpx`, `pydantic`, `pydantic-settings`, `numpy`,
`opencv-python-headless`, `pytest`, `pytest-asyncio`) and actually run
`pytest` — 74 tests, all passing, a first for this project. Maven Central
remains unreachable (confirmed separately, `host_not_allowed`), so this
does not extend to the backend's JUnit suite; and a full
`pip install -r requirements.txt` (pulling in `torch`/`ultralytics`/
`google-generativeai`) exhausted this workspace's disk quota mid-install,
so those three heavy ML dependencies remain genuinely NOT VERIFIED — only
the lightweight set above was actually exercised. `yolo_service.py`'s own
lazy `from ultralytics import YOLO` (placed inside `_ensure_loaded`,
reached only when a real weights file exists on disk — which none do in
any environment this project has run in) meant the "no trained model"
code path was still fully testable without ever importing `ultralytics`
itself. **Future phases should re-check this network assumption again
before assuming it's still true or still false** — environment
capabilities are not guaranteed stable across sessions, per the general
principle already established for every other "no live X" constraint in
this document.

### Phase 20 — A real test-fixture bug found by actually running `test_preprocessing.py`, not by manual review

`test_flags_dark_image_without_rejecting` (Phase 7) scaled a synthetic
checkerboard image's brightness by `0.05` to simulate a dark photo. Manual
review (the only validation method available every prior phase) missed
that this also crushes the image's edge contrast — Laplacian variance
scales with the *square* of the brightness-scale factor — pushing the
fixture's blur-variance below `blur_variance_threshold` (80.0) before the
darkness check ever ran. `_assess_quality`'s real if/elif chain checks
blur before darkness (intentional production behavior: a photo that's
both blurry and dark is more actionably reported to the citizen as
"blurry"), so the test was actually asserting the wrong branch and would
have failed the moment anyone actually ran it — which nobody had, until
this phase. **Fixed:** the fixture now scales brightness by `0.3`
(empirically verified: mean brightness ~33, under the 35.0 darkness
threshold, while blur-variance stays ~2680, well clear of the 80.0
threshold) so the test now genuinely exercises the `TOO_DARK`-without-
`BLURRY` path its name claims to test. `preprocessing.py`'s own
production logic needed no change — this was purely a test-fixture bug,
the first one this project has ever actually caught by execution rather
than by code review, and a direct illustration of why "manually validated,
NOT VERIFIED" carries real residual risk that only running the tests
removes.

### Phase 20 — H2 added as a test-scope-only dependency for `@DataJpaTest` repository tests

No in-memory database dependency existed anywhere in `backend/pom.xml`
before this phase — `mysql-connector-j` (runtime scope) was the only DB
driver, meaning no repository-layer test could ever run against anything
but a real, external MySQL instance. **Decided:** add `com.h2database:h2`
at `scope=test` only, plus a new `test`-profile
`application-test.yml` configuring it in MySQL compatibility mode
(`MODE=MySQL`) so `ComplaintRepositoryTest` runs the real, unmodified
Flyway migrations under `database/migrations/` against it rather than
maintaining a second, H2-flavored schema copy that could silently drift
from the real one. This does **not** change the locked technology stack —
MySQL remains the only driver on the production/runtime classpath; H2 is
never packaged into the application jar. **NOT VERIFIED end-to-end**
(same Maven Central constraint as every other backend test this phase) —
the JSON-typed columns in `V1`/`V8`/`V12` are flagged in both `pom.xml`'s
dependency comment and `application-test.yml` as the most likely
first-run friction point for whoever eventually runs this for real in a
Maven-enabled environment.

### Phase 20 — Which backend test categories were and weren't written this phase

Given the size of the backend (11 controllers, ~20 services, ~15
repositories) and the "don't rewrite working functionality just to create
tests" instruction, this phase wrote representative, high-value coverage
rather than an exhaustive one-test-file-per-class sweep, prioritized by
where this document's own history flags the highest risk: the Phase 13
department-scope security fix (`ComplaintServiceTest`), the SRS 15.2
login-lockout/MFA rules (`AuthServiceTest`), JWT issuance/validation
(`JwtServiceTest`), the stateless auth filter (`JwtAuthenticationFilterTest`),
the complaint lifecycle's transition table (`ComplaintStateMachineTest`),
notification retry-with-backoff (`NotificationServiceTest`), and
department/officer routing (`DepartmentAssignmentServiceTest`), plus one
`@WebMvcTest` controller-slice example (`ComplaintControllerTest`,
`@PreAuthorize` role-gate regression coverage) and one `@DataJpaTest`
repository example (`ComplaintRepositoryTest`). **Explicitly NOT covered
this phase** (a real gap, not a hidden one): admin/settings services,
dashboard/analytics services, `EscalationSchedulerService`, and the
remaining ten controllers have no dedicated test file yet. A future
phase revisiting `backend/src/test/` should treat this list, not "testing
is done," as the honest starting point.

### Phase 20 — Flutter: unit/widget tests added with no mock-HTTP tooling; `LoginScreen`'s happy path stays untested

No `flutter`/`dart` SDK exists in this workspace (confirmed: `which dart`
finds nothing) — every Flutter test written this phase is manually
validated only, the same "NOT VERIFIED" category carried since Phase 17.
No mocking dev-dependency (`mockito`, `http_mock_adapter`, or similar)
existed in `pubspec.yaml` before this phase and none was added — adding
one to properly mock `ApiClient`/`TokenStorage` for a real HTTP-flow test
would be a bigger scope change than this phase's testing brief covers.
**Decided:** write what's honestly testable without one: the
`ComplaintStatus` wire-name/label mapping, the `Complaint`-model family's
`fromJson` null-safety defaults, `StatusBadge`'s full-enum rendering
sweep, and `LoginScreen`'s static widget tree plus its two navigation
links — and, as a genuine failure/edge-case test, that a real network
failure during "Sign In" surfaces a user-visible error message and
re-enables the button rather than leaving the UI stuck or crashing
(exercisable without mocking, since the real `ApiClient`/`TokenStorage`
singletons genuinely do throw in this networkless sandbox). **Left
untested:** every screen's happy-path submit flow, since none is
reachable without either a live backend or new mock-HTTP tooling this
phase didn't add.

### Phase 20 — Database migration validation: a real, executable script, not just a documentation note

Unlike the backend/AI-service/Flutter application code, static analysis
of the Flyway `.sql` migration files themselves needs no JVM, no Python
package installs, and no Flutter SDK — just a text/filesystem scan. This
phase wrote `database/validation/validate_migrations.py` and **actually
ran it** (not NOT-VERIFIED): confirmed all 18 migrations are sequentially
numbered V1–V18 with no gaps or duplicates; every `REFERENCES` clause's
target table already exists (created in an earlier migration, or via
self-reference within the same `CREATE TABLE`) by the time it's used;
every file's parentheses are balanced and ends with a semicolon-terminated
statement; and cross-referencing every JPA entity's `@Column`/
`@JoinColumn` names against the migration-derived column sets found zero
real drift (the one initial false positive — `RefreshToken.familyId` vs.
`refresh_tokens.family_id CHAR(36)` — was the validation script's own
regex missing `CHAR` from its type-keyword list, not an actual schema
mismatch; fixed by widening `TYPE_KEYWORDS` before this script was
finalized as a repo deliverable). This does not replace actually running
`mvn flyway:migrate`/letting Spring Boot's Flyway auto-run execute
against a real MySQL instance (still NOT VERIFIED, carried forward since
Phase 3) — it is a real but narrower guarantee: the migrations are
*internally consistent*, not that they're syntactically valid MySQL DDL
end-to-end (a static regex scan cannot fully confirm that).

### Phase 21 — No CI/CD requirement exists in the SRS; scope came entirely from ARCHITECTURE.md's Phase → Component map

The uploaded `JanNet_AI_SRS_BRD_FRS.docx` was scanned directly (via
`python-docx`, per this project's established convention) for any
CI/CD-adjacent term — "CI/CD", "GitHub Action", "continuous
integration"/"continuous deployment", "pipeline", "workflow", "deploy" —
and none were found. This is consistent with that document predating
this project's locked Spring Boot/MySQL/Flutter/FastAPI stack in the
first place (the very first documented deviation, recorded in this
section's Phase 1 entry). Phase 21's concrete scope was therefore
derived entirely from `ARCHITECTURE.md` Section 8's Phase → Component
map (`21 | .github/workflows/`) and this phase's own explicit brief, not
from the SRS — the same "derive scope from architecture/instructions
when the SRS is silent or stale" pattern already used at Phase 17 (full
endpoint audit) and Phase 20 (component name already given, but no
concrete requirements list existed for what "testing" meant).

### Phase 21 — Two small pom.xml/requirements.txt additions were judged CI/CD-scope, not application-scope

`backend/pom.xml` gained a `jacoco-maven-plugin` execution and
`ai-service/requirements.txt` gained `pytest-cov` this phase, even
though this phase's instructions said "no application source changed."
The judgment call: both are build-tooling-only additions that exist
*solely* to produce the coverage artifacts `backend-ci.yml`/
`ai-service-ci.yml` upload — neither changes what the application does
at runtime, neither is reachable from any `main`/`app` source path,
and both are the same category of change as Phase 20's own H2
test-scope dependency addition (also to `pom.xml`, also test-tooling,
also not counted as "application source" in that phase's own scope
accounting). No coverage *threshold* was enforced via JaCoCo's `check`
goal deliberately — Phase 20's own `PHASE_HANDOFF.md` already documents
several intentionally-uncovered service/controller areas, and gating CI
on an arbitrary coverage percentage would penalize that phase's honest
gap-reporting rather than serve this phase's actual CI/CD-wiring goal.

### Phase 21 — Full Postman/Newman regression automation deliberately not attempted

`docker/docker-compose.yml`'s new smoke test (in `docker-build.yml`)
could, in principle, also run the Phase 19 Postman collection end-to-end
with Newman against the live stack it brings up. This was deliberately
not done: `postman/README.md` documents that the collection's auth flow
requires a human to manually copy a login response's `accessToken` into
the environment before dependent requests will authenticate — there are
no pre-request/test scripts in the collection that capture and chain
tokens automatically. Adding those scripts would mean modifying
`postman/JanNet_AI.postman_collection.json`, which is Phase 19's
component per `ARCHITECTURE.md` Section 8, not Phase 21's. Rather than
either silently skip contract-level verification entirely or reach
outside this phase's own boundary to "fix" a different phase's
deliverable, `docker-build.yml`'s smoke test instead verifies both
services' own `/health`/`/actuator/health` endpoints — genuine,
run-every-time verification that the compose stack comes up and both
processes are live together, short of full request/response contract
testing. This limitation is documented, not hidden, in
`.github/workflows/README.md`'s "What this phase deliberately did NOT
build" section.

### Phase 21 — Trivy image scanning is report-only for now, not a hard-failing gate

`docker-build.yml` scans both newly-built images with Trivy and uploads
SARIF results to the repository's Security tab, but runs with
`exit-code: 0` rather than failing the build on CRITICAL/HIGH findings.
Rationale: both base images (`eclipse-temurin`, `python:3.11-slim`) can
carry upstream OS-package CVEs this project cannot patch by editing its
own source, and this workspace has never had a Docker daemon to test
whether a hard gate would make every single build red for reasons
unconnected to this phase's actual CI/CD wiring, before that gate has
ever run once for real. This is a deliberate, documented initial choice
— not an oversight — with an explicit note (in both `docker-build.yml`
and `.github/workflows/README.md`) to revisit tightening it once a real
run history exists to baseline accepted findings against. Similarly, no
OWASP `dependency-check-maven`-style hard vulnerability gate was added
for the backend: as of 2024 that plugin requires a provisioned NVD API
key this project has never had reason to obtain, and would introduce a
flaky external dependency into every ordinary backend CI run for
coverage CodeQL (`codeql.yml`) and Dependabot (`dependabot.yml`) already
provide between them without one.

### Phase 21 — `release-image-publish.yml` publishes images; it does not deploy anything (the Phase 21/22 boundary)

This phase's brief listed "deployment pipeline preparation" as an
in-scope CI/CD item while also explicitly warning not to build
deployment behavior belonging to Phase 22. The line drawn:
`release-image-publish.yml` builds both service images and pushes them,
tagged with the real git tag, to GitHub Container Registry (GHCR) — but
only on an explicitly-pushed `v*.*.*` tag or a manual dispatch, never as
a side effect of ordinary CI, and it does not reference, connect to, or
provision any server, cluster, or cloud target. "Publish a versioned,
scannable artifact to a registry" and "deploy that artifact somewhere"
are two distinct steps in any real pipeline; only the first is CI/CD
automation, and only the first was built this phase. `deployment/`
(per `ARCHITECTURE.md` Section 8, Phase 22's reserved component) remains
completely untouched — confirmed via this phase's own diff audit.

### Phase 22 — AWS was this phase's own instruction, not an SRS mandate; every sizing/topology default traces to the SRS's provider-agnostic low-cost constraint

`JanNet_AI_SRS_BRD_FRS.docx` was re-scanned directly via `python-docx`
for any cloud-provider-specific requirement — none exists. Section 30's
Constraints clause states only that the deployment should run on
low-cost/open-source infrastructure rather than enterprise managed
services, without naming AWS, GCP, or Azure. AWS was named by this
phase's own explicit instructions, not derived from the SRS. Given that,
every Terraform sizing/topology decision (`t3.small` over a larger
instance, single-AZ RDS with `multi_az = false` by default, no NAT
Gateway, no ALB/ECS/Kubernetes) traces directly back to Section 30's
constraint and Section 7's "avoid overengineering" non-goal, the same
way this project has resolved every other SRS-tech-stack tension since
Phase 1 (the locked Spring Boot/MySQL/Flutter stack itself overriding
the SRS appendix's PostgreSQL/FastAPI/React.js references). If a real
production launch later needs Multi-AZ, an ALB, or a different cloud
provider entirely, that is a genuine new requirement to document here
when it happens, not something this phase should have guessed at.

### Phase 22 — Object storage stays on the EC2 host's own EBS volume this phase; no `S3StorageService` was written

The `StorageService` interface (Phase 6) was deliberately designed so a
future S3 backend could be a swap-in with zero `ComplaintService`
change — and `docker/docker-compose.yml`'s own Phase 18 comment
explicitly names "a future phase" as when that swap should happen. Phase
22 is arguably that future phase, but writing a new
`S3StorageService.java` class is backend **application source** work,
and `ARCHITECTURE.md` Section 8 names Phase 22's own component as
`deployment/` only — not `backend/`. Resolved by keeping
`LocalStorageService` unchanged and instead backing it with a real,
persistent EBS volume on the production EC2 host (`ec2_root_volume_gb`,
default 30 GB) — a real, working, if less durable, storage backend
appropriate for a not-yet-launched academic pilot's scale. The trade-off
is real and is documented, not hidden: photos are lost if that EBS
volume is lost, and no automated snapshot schedule exists yet either
(also documented as an open item). A future phase that touches
`backend/` should write the actual `S3StorageService` implementation;
this phase's job was limited to making the current implementation
survive a production deployment, not migrating it.

### Phase 22 — RDS's `require_secure_transport = ON` required a new prod-profile-only JDBC TLS override, caught by static review before packaging

Enforcing TLS at the database layer (`aws/terraform/rds.tf`'s custom
parameter group, satisfying SRS 27.2's "TLS 1.2+ for internal
service-to-service communication") would have silently broken database
connectivity outright against the inherited `application.yml`'s base
JDBC URL (`useSSL=false&allowPublicKeyRetrieval=true`, correct only for
the local docker-compose `mysql:8.0` container, which presents no
certificate and enforces no such requirement). This was caught during
this phase's own manual cross-file consistency review, not left for a
live `terraform apply` to discover as a first-boot failure. Resolved
with a new, additive-only override in
`application-prod.yml`:
`useSSL=true&requireSSL=true&verifyServerCertificate=false&allowPublicKeyRetrieval=true&...`
— applying only under the `prod` Spring profile; `local`/`dev`/`test`
behavior is completely unchanged. `verifyServerCertificate=false` is a
deliberate, documented trade-off: the connection is encrypted
(satisfying the TLS-in-transit requirement) but the RDS server
certificate's chain is not validated against AWS's own CA bundle. Full
certificate pinning would require packaging that CA bundle into the
runtime image and wiring a Java truststore path — a real, valid
hardening step, left open rather than attempted without a live RDS
certificate reachable in this sandbox to actually test against.

### Phase 22 — Monitoring/alerting scope: infrastructure-level only; the SRS's four application-level alert conditions stay an open item

SRS Section 28 names four specific automated alert conditions: API
error rate exceeding 5%, AI classification P95 latency exceeding 15s,
scheduled analytics job failures, and SLA-breach rate exceeding a
configured threshold. All four require the backend/ai-service
application code itself to emit custom metrics (e.g. a Micrometer
counter/timer wired into a CloudWatch or Prometheus exporter) — no such
metric-emission code exists anywhere in `backend/` or `ai-service/` as
of Phase 21, and writing it is backend/ai-service **application source**
work, outside this phase's `deployment/`-only scope per `ARCHITECTURE.md`
Section 8. What this phase did build, because it needs no application
code change at all: infrastructure-level health/availability alerting —
an EC2 status-check-failed alarm, and RDS CPU/free-storage alarms, all
publishing to one SNS topic with an optional email subscription. This is
a real, working baseline, but it is explicitly narrower than what SRS 28
literally asks for — stated here and in `deployment/README.md`'s "Known
gaps" rather than claimed as complete monitoring coverage.

### Phase 22 — No AWS access key, anywhere, at any layer: EC2 uses an IAM instance role, GitHub Actions uses OIDC federation

Following this project's own "no secret values live in source control"
convention (established at `.env.example`'s creation in Phase 4, and
extended into CI at Phase 21 with `openssl rand`-generated ephemeral
credentials for the Docker smoke test), Phase 22 extends the same
principle one layer further: not even a *long-lived AWS credential*
exists anywhere in this project's configuration. The EC2 app host
authenticates to SSM Parameter Store, Secrets Manager, and CloudWatch
purely via its own IAM instance role (`aws/terraform/iam.tf`) — no
`aws configure` step, no credentials file, ever touches that host.
`.github/workflows/deploy-aws.yml` authenticates to AWS purely via
OpenID Connect federation to a purpose-built IAM role
(`aws/terraform/github_oidc.tf`), whose trust policy is scoped to this
exact repository's tag-push and `main`-branch `workflow_dispatch`
events only — GitHub issues a short-lived, automatically-expiring token
per run; nothing is stored as a GitHub secret at all, only two
non-sensitive repo *variables* (an instance ID and a role ARN).

### Phase 22 — Live AWS execution was not possible in this sandbox; every deliverable is complete configuration, staticly validated, not run

This workspace's own `network_configuration` allowed-domains list was
checked before any file was written: it includes package registries
(`pypi.org`, `npmjs.org`, `crates.io`) and `github.com`/
`codeload.github.com`, but no AWS API endpoint and no
`registry.terraform.io`. Combined with no AWS account/credentials and no
Docker daemon, this meant zero live execution was possible for this
phase's actual deliverable — a materially larger verification gap than
any prior phase (even Phase 18's "no Docker daemon" or Phase 21's "no
GitHub Actions runner," each of which still had *some* locally-runnable
validation path). Per this phase's own explicit instruction ("if live
AWS deployment cannot be performed ... prepare the complete deployment
configuration/instructions, validate everything that can be validated
locally, clearly document what could not be executed, do not falsely
claim a live deployment"), the scope was set accordingly from the start:
complete, real, cross-checked Terraform/shell/YAML/nginx configuration,
with every syntax-level claim backed by an actual local check (`bash
-n` on every script, `yaml.safe_load` on the new workflow — both
commands and their pass/fail output captured in
`deployment/VERIFICATION.md`) rather than asserted from memory. No live
deployment result of any kind is claimed anywhere in this phase's
documentation.

### Phase 23 — Real MySQL 8 execution surfaced a genuine InnoDB defect in `V6__create_complaints.sql` — RESOLVED this phase

Every prior phase's validation of `database/migrations/*.sql` was
static-only (manual SQL reading, `database/validation/validate_migrations.py`'s
version-sequence/FK-ordering/column-drift checks) — no phase before this
one had a real MySQL server reachable in-sandbox to actually run the
migrations against (flagged unchanged as an open gap since Phase 3).
Phase 23 installed MySQL 8.0.46 for the first time and ran all 18
migrations in order for real.

`V6__create_complaints.sql` failed on the very first attempt with MySQL
error 3823: `Column 'parent_complaint_id' cannot be used in a check
constraint 'chk_complaints_not_self_parent' because it is a part of a
foreign key constraint that has referential action`. This is a
documented MySQL/InnoDB platform restriction: a column carrying a
foreign key's referential action (here, `fk_complaints_parent_complaint`'s
self-referencing `ON DELETE SET NULL`) cannot also participate in a
CHECK constraint. It is not specific to this schema's modeling — it
applies to any table where a FK-with-action column is also
CHECK-constrained — and it only manifests against a real server; no
amount of brace/paren balance checking or manual SQL reading (this
project's only prior validation method for `.sql` files) could have
caught it.

**Resolution (Phase 23):** removed the `chk_complaints_not_self_parent`
CHECK constraint rather than reworking it into a `BEFORE INSERT`/`BEFORE
UPDATE` trigger. Confirmed safe before removing: the identical rule —
rejecting a complaint whose `parent_complaint_id` equals its own
`complaint_id` — is already enforced at the application layer in
`ComplaintService`'s DUPLICATE-decision branch (`reviewByVerificationTeam`),
which throws `InvalidStateTransitionException` with a human-readable
message before any row referencing the offending pair is ever written.
The CHECK constraint was pure defense-in-depth against a case the
application code already makes structurally unreachable through the
API, not the sole guard, so removing it introduces no real integrity
gap. The removal and its full rationale are documented inline in the
migration file itself, immediately where the constraint used to sit, so
a future reader hits the explanation at the exact point they'd look for
the constraint. All 18 migrations re-ran clean after the fix; all 15
expected tables created. `validate_migrations.py` re-run clean against
the fixed set.

This is the single highest-value finding of this entire 23-phase
project's final phase: the one gap every phase's own "Key learnings"
section (see `ARCHITECTURE.md`) predicted would only be catchable by
real execution, caught by real execution, on the first real attempt.

### Phase 23 — Stray `deployment/{aws` directory from Phase 22 packaging — RESOLVED this phase

Inspecting the Phase 22 zip's top-level structure before any Phase 23
work began surfaced an empty, malformed directory literally named
`deployment/{aws` (confirmed empty via a recursive `find` before
touching it). This is almost certainly the result of a shell
brace-expansion command — most plausibly something shaped like `mkdir -p
deployment/{aws,terraform,scripts,nginx,ssm}` — that was executed in a
way that didn't expand the brace pattern (e.g. under `sh` rather than
`bash`, or with the pattern accidentally quoted), so the literal string
`{aws` became part of a directory name instead of expanding to five
sibling directories. The real, correctly-named `deployment/aws/`,
`deployment/scripts/`, `deployment/nginx/`, and `deployment/ssm/`
directories all already existed alongside it with their real content
intact — this stray directory was pure packaging debris, not evidence
of any missing or lost deliverable. Deleted this phase; confirmed via a
full `diff -rq` against the untouched Phase 22 baseline extraction that
its removal is the only structural change besides the `V6` migration
fix above.

### Phase 23 — Final phase scope boundary: verification and genuine-defect-fixing only, explicitly not a Phase 24

This phase's own instructions were explicit that it is the final phase
and that no Phase 24 exists. Applying this project's own established
"additive-only changes per phase" and "phase-boundary discipline is
inviolable" principles (see `ARCHITECTURE.md`'s Key learnings) to a
*final* phase means something slightly different than it has for Phases
2-22: there is no future phase left to hand an open item to. Each of the
explicitly-carried-forward gaps re-listed in this phase's
`PHASE_HANDOFF.md` entry (S3 storage, application-level CloudWatch
alerts, push notification delivery, Officer/Citizen Dashboards, the full
Reports Module, several Phase-17 SRS-alignment items, RDS certificate
pinning, a signed Flutter release build) was deliberately **not**
addressed this phase, even though it is the last one — because this
phase's own explicit instructions were "do not invent missing features"
and "fix only issues required for final project completion," and every
one of those items was already a deliberate, documented scope decision
made by an earlier phase with the user's own instructions at the time
(not an oversight this phase could correct without exceeding its
verification-only mandate). They are recorded here, once more, as the
final, permanent state of this project's known limitations — not as
work silently deferred to a phase that will never exist.
