# JanNet AI — Postman Collection (Phase 19)

This directory contains the Phase 19 deliverable: a Postman collection and
paired environment covering every API endpoint that actually exists in the
Phase 18 codebase — the Spring Boot backend and the Python FastAPI AI
microservice. No endpoint here is speculative or planned for a future
phase; every request was built directly from the current controller/route
source files (see VALIDATION METHODOLOGY below), not from the SRS's
"representative endpoints" prose alone.

## Files

- `JanNet_AI.postman_collection.json` — the collection itself (Postman
  Collection Schema v2.1.0). 56 requests across 12 folders.
- `JanNet_AI_Local.postman_environment.json` — a paired environment with
  `baseUrl`/`aiServiceBaseUrl` defaulted to `http://localhost:8080` /
  `http://localhost:8001` (matches both a natively-run backend and a
  `docker compose up` deployment, since `docker/docker-compose.yml`
  publishes both services to those same host ports — see
  `docker/README.md`).

## Import instructions

1. Postman → Import → select both JSON files (or drag-and-drop them).
2. Select the "JanNet AI - Local" environment from the environment
   dropdown (top-right) before running any request.
3. Set `aiServiceApiKey` in the environment to match whatever
   `AI_SERVICE_API_KEY` your running `ai-service` instance was started
   with (the environment file ships with the same placeholder default as
   `.env.example`; it will not work against a real deployment that has
   changed it).

## Typical workflow

1. **Authentication** folder → `Login` (or `Register` → `Verify OTP` →
   `Login` for a brand-new citizen account). Copy the response's
   `accessToken`/`refreshToken` into the environment's `accessToken`/
   `refreshToken` variables — every other backend request in this
   collection uses `{{accessToken}}` as a Bearer token automatically via
   the request-level Authorization config.
2. For staff-tier folders (Complaints' officer/department-head actions,
   Departments, Government Dashboard, all of Admin), log in as an account
   holding the required role. Self-registration only ever creates a
   `CITIZEN` account — staff accounts come from **Admin - Users → Create
   Staff User**, or from the `BOOTSTRAP_SUPER_ADMIN_*` variables on a
   fresh database (see `docker/README.md`) to get a first Admin/Super
   Admin login to provision everyone else from.
3. **AI Service (Internal)** folder requests use `X-Internal-Api-Key`
   instead of a bearer token — these are normally called by the backend
   itself (`AiServiceClient`), never by Flutter, but are included here so
   the AI microservice can be exercised and debugged in isolation.

## What's documented on every request

- The exact method, path, required role(s) (`@PreAuthorize` expression
  copied from the controller source), and required headers.
- A realistic example request body (JSON or `multipart/form-data`, matching
  whichever the real endpoint actually consumes).
- At least one saved example response for the success case, and at least
  one for a realistic failure case (validation error, 401/403 role
  mismatch, 404, 409 business-rule conflict, etc.) — sourced from the
  actual exception types each service layer throws
  (`GlobalExceptionHandler` for the backend, `app/core/exceptions.py` for
  ai-service), not invented status codes.
- A short description block quoting or paraphrasing the controller's own
  Javadoc/docstring where that Javadoc explains *why* the endpoint behaves
  the way it does (e.g. why `/complaints/{id}/assign` is separate from
  `/complaints/{id}/status`), so this collection also works as a quick
  API-behavior reference, not just a request list.

## Error envelope shapes (two, by design)

- **Backend** (`{{baseUrl}}`): `{ timestamp, status, error, message, path,
  details[] }` on every non-2xx JSON response
  (`GlobalExceptionHandler`/`ErrorResponse.java`, locked Phase 3).
- **AI service** (`{{aiServiceBaseUrl}}`): `{ error_code, message, details
  }` — this is SRS 20.6's own literal error envelope for this service;
  see `PROJECT_INTEGRATION.md` Section 2 for why the two were never
  reconciled into one shape.

## Pagination

Every list endpoint backed by Spring Data (`GET /complaints`, `GET
/notifications`, `GET /admin/users`, `GET /admin/audit-logs`) returns a
full Spring Data `Page` JSON envelope (`content`, `totalElements`,
`totalPages`, `number`, `size`, `first`, `last`, `numberOfElements`,
`empty`), driven by `page`/`pageSize` query parameters — reflected in each
of those requests' example responses.

## What's deliberately NOT in this collection

- Any endpoint that doesn't exist yet. Three items were checked
  specifically and confirmed absent, matching `ARCHITECTURE.md` Section
  9's own "Explicitly left open" list from Phase 17: a public
  (pre-authentication) ward-lookup variant, a Citizen/Officer-level
  dashboard endpoint (SRS 24.1/24.2 — only the Department Head/Admin
  dashboard from Phase 16 exists), and any budget-amount field on
  `ComplaintResponse` (the `/approve-budget` action itself is real and is
  included; there is simply no endpoint yet that returns the underlying
  budget figures for a citizen or officer to review before approving).
- Actuator/Swagger UI endpoints (`/actuator/health`, `/swagger-ui/**`,
  `/v3/api-docs/**`) — these are operational/introspection endpoints, not
  part of the JanNet AI business API surface this collection documents.
- A `docker-compose`-internal-hostname environment variant
  (`http://backend:8080` / `http://ai-service:8001`) — Postman itself
  always runs on the host machine, never inside the Compose network, so
  only the host-published ports are ever reachable from it.

## Validation methodology (no live server available in this environment)

Since no live backend/ai-service instance is reachable from this
workspace (same category of constraint as every prior phase's "no live
JDK/Maven/MySQL/Flutter SDK" — see `PROJECT_PROGRESS.md`'s carried-forward
items), this collection could not be validated by actually firing requests
at a running deployment. Instead:

1. Every one of the 56 requests' method + path was cross-checked
   one-for-one against the live `@GetMapping`/`@PostMapping`/
   `@PutMapping`/`@PatchMapping` annotations in all eleven backend
   `controller/*.java` files and all four `ai-service/app/api/routes/*.py`
   route modules — not copied from `PROJECT_INTEGRATION.md`'s prose
   summary, which was used only as a cross-reference, never as the
   primary source.
2. Every request body field was cross-checked against the actual DTO
   record's fields and Jakarta Bean Validation annotations (backend) or
   Pydantic model fields (ai-service) — not invented or assumed from the
   SRS's field-name conventions alone.
3. Every `@PreAuthorize` role list was copied verbatim from the
   controller source onto the corresponding request's description.
4. Every example success-response shape was built from the exact
   response DTO's fields (e.g. `ComplaintResponse`, `AuthResponse`,
   `GovernmentDashboardResponse`), including nested types
   (`LocationResponse`, `StatusHistoryResponse`, `KpiTilesResponse`, etc.)
   — no field was invented that doesn't exist on the real DTO.
5. Every example failure-response was matched to a real exception type
   the relevant service method actually throws (cross-referenced against
   `GlobalExceptionHandler.java` for the backend and
   `ai-service/app/core/exceptions.py` for ai-service), with a status code
   taken from that handler's own `@ExceptionHandler` mapping — not a
   generically plausible status code.
6. The full collection JSON was parsed with Python's `json.load` to
   confirm well-formed JSON, and every `{{variable}}` placeholder used
   anywhere in the collection was cross-checked to have a matching
   declaration in either the collection's own `variable` array or the
   paired environment file (zero undeclared variables found).

**NOT verified**: this collection has not been run against a live
`docker compose up` deployment or a live `mvn spring-boot:run` +
`uvicorn` pair — no such environment is reachable from this workspace.
Running it end-to-end against a real deployment remains part of the same
outstanding "correct on paper, unverified in practice" item flagged since
Phase 3 (full Flyway migration + `mvn clean verify` against real MySQL),
now joined by Phase 18's Docker images and this phase's own collection.
Whoever next has real Docker/JDK/MySQL access should run this collection
with Newman (`newman run JanNet_AI.postman_collection.json -e
JanNet_AI_Local.postman_environment.json`) against a live stack and file
any drift discovered as its own follow-up item — see
`PROJECT_PROGRESS.md`'s "INTEGRATION REQUIREMENTS FOR NEXT PHASE" for
where this is now recorded.
