# RBAC / Authorization Matrix

Gap-backlog Patch 22 (Sep 2026 audit) deliverable. Every row below was
extracted directly from the real `@PreAuthorize` annotations and
`SecurityConfig` rules in this codebase (a script walked every
controller's method + its annotation - see "How this was generated"),
not written from memory of what the roles are *supposed* to be.

Roles: **CITIZEN**, **GOVERNMENT_OFFICER**, **MAINTENANCE_TEAM**,
**DEPARTMENT_HEAD**, **VERIFICATION_TEAM**, **ADMIN**, **SUPER_ADMIN**.

## Complaints (`/api/v1/complaints`)

| Endpoint | Method | Allowed roles (controller `@PreAuthorize`) | Further scoping (in the service layer) |
|---|---|---|---|
| `/complaints` | POST (create) | CITIZEN | — |
| `/complaints/{id}` | GET | any authenticated | `requireCanView`: CITIZEN only their own; GOVERNMENT_OFFICER only if assigned to them; DEPARTMENT_HEAD only their own department; VERIFICATION_TEAM/MAINTENANCE_TEAM/ADMIN/SUPER_ADMIN unrestricted |
| `/complaints` | GET (list) | any authenticated | same scoping as above, applied to the query |
| `/complaints/{id}/verify` | PATCH | VERIFICATION_TEAM, ADMIN, SUPER_ADMIN | — |
| `/complaints/{id}/reopen` | POST | CITIZEN | must be the complaint's own citizen; RESOLVED/CLOSED only; grace period enforced |
| `/complaints/{id}/status` | PATCH | GOVERNMENT_OFFICER, MAINTENANCE_TEAM, DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN | `requireCanView` scoping applies |
| `/complaints/{id}/classification` | PATCH | GOVERNMENT_OFFICER, DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN | `requireCanView` scoping applies |
| `/complaints/{id}/escalate` | PATCH | GOVERNMENT_OFFICER, DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN | `requireCanView` scoping applies |
| `/complaints/{id}/notes` | POST | GOVERNMENT_OFFICER, DEPARTMENT_HEAD, VERIFICATION_TEAM, ADMIN, SUPER_ADMIN | `requireCanView` scoping applies |
| `/complaints/{id}/assign` | PATCH | DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN | — |
| `/complaints/{id}/approve-budget` | PATCH | DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN | — |
| `/complaints/{id}/rating` | POST/GET | CITIZEN | must be the complaint's own citizen; RESOLVED/CLOSED only for POST (Gap-backlog Patch 11) |
| `/complaints/{id}/appeal` | POST | CITIZEN | must be own complaint, REJECTED only (Gap-backlog Patch 12) |
| `/complaints/{id}/appeal` | GET | any authenticated | owner citizen or any staff role (`isOwner \|\| isStaff` in `ComplaintAppealService.listForComplaint`) — controller `@PreAuthorize` added this audit for defense-in-depth consistency with `getDetail`/`list` above; was previously relying only on the `/complaints/**` blanket-authenticated SecurityConfig rule (not a real hole - service-level check was already correct - but inconsistent with this controller's own pattern) |
| `/complaints/appeals/pending` | GET | VERIFICATION_TEAM, DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN | — |
| `/complaints/appeals/{appealId}/review` | PATCH | VERIFICATION_TEAM, DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN | — |

## Departments (`/api/v1/departments`)

| Endpoint | Method | Allowed roles |
|---|---|---|
| `/departments` | GET | any authenticated |
| `/departments/{id}/officers` | GET | DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN |
| `/departments/{id}/performance` | GET | DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN |

## Government dashboards (`/api/v1/dashboard`)

| Endpoint | Method | Allowed roles |
|---|---|---|
| `/dashboard/overview` | GET | DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN |
| `/dashboard/department-comparison` | GET | ADMIN, SUPER_ADMIN |
| `/dashboard/admin-summary` | GET | ADMIN, SUPER_ADMIN |

## Admin-only modules

| Controller | Endpoints | Allowed roles |
|---|---|---|
| `AdminAuditLogController` | GET list | ADMIN, SUPER_ADMIN |
| `AdminRoutingRuleController` | POST create, GET history, PATCH deactivate | ADMIN, SUPER_ADMIN |
| `AdminRoutingRuleController` | GET list-active | ADMIN, SUPER_ADMIN, DEPARTMENT_HEAD |
| `AdminSettingsController` | GET list, PATCH update | ADMIN, SUPER_ADMIN |
| `AdminUserController` | all (list, create, role, status, reset-password, revoke-sessions) | ADMIN, SUPER_ADMIN |

## Self-service (every authenticated role)

| Controller | Endpoints | Rule |
|---|---|---|
| `UserController` | `/me`, `/me/settings` (GET/PUT/PATCH) | `isAuthenticated()` - always the caller's own profile, via `principal.getUser()` |
| `NotificationController` | `/notifications`, `/notifications/preferences` | no method-level `@PreAuthorize` - covered by `SecurityConfig`'s explicit `/api/v1/notifications/**` → `authenticated()` rule; always scoped to `principal.getUser()`, so no role distinction is meaningful here |
| `WardController` | `/wards`, `/wards/{id}` | `isAuthenticated()` - read-only reference data |
| `AuthController` | `/logout-all` | `isAuthenticated()` |
| `AuthController` | register/verify-otp/resend-otp/login/mfa/refresh/logout/forgot-password/reset-password | none - the SRS-named pre-JWT exceptions, `permitAll` in `SecurityConfig` |

## Public (no authentication)

| Controller | Endpoints | Why |
|---|---|---|
| `PublicWardController` | `/public/wards` | Gap-backlog Patch 8 - registration needs a ward picker before any JWT exists; `WardResponse` is already documented public-safe (id/name/code only) |
| `ImageContentController` | `/images/content` | Gap-backlog Patch 6 - protected by an HMAC-signed, time-limited link instead of a JWT; see that controller's Javadoc for why "public" ≠ "unauthorized" here |
| `/actuator/health`, `/actuator/info` | — | standard health-check exposure |
| `/swagger-ui/**`, `/v3/api-docs/**` | — | API documentation |

## Default (fail-closed)

Any endpoint not explicitly listed above falls to `SecurityConfig`'s
`.anyRequest().authenticated()` - deliberately not `permitAll` by
default, so a forgotten rule fails closed (see that class's own header
comment). No endpoint in this codebase currently relies on that fallback
alone for anything beyond "must have a valid JWT" - every
role-restricted endpoint has an explicit `@PreAuthorize`.

## How this was generated

A script walked every `*.java` file under `controller/`, paired each
`@GetMapping`/`@PostMapping`/`@PatchMapping`/`@PutMapping` with the
`@PreAuthorize` annotation (if any) immediately above it, and printed
every method's real, current annotation - not a manually-maintained
list that could drift from the code. Re-run when adding a new endpoint:

```
cd backend/src/main/java/com/jannetai/backend/controller
for f in *.java; do
  awk '
    /@GetMapping|@PostMapping|@PatchMapping|@PutMapping|@DeleteMapping/ { map=$0 }
    /@PreAuthorize/ { auth=$0 }
    /public [A-Za-z<>,? ]+ [a-zA-Z]+\(/ {
      if (map != "") { print FILENAME " | " map " | " auth; map=""; auth="" }
    }
  ' "$f"
done
```

## Known gaps / not yet covered by an automated test

- This document is generated from the *annotations*, which is a
  necessary but not sufficient check - it confirms what SHOULD be
  enforced, not that a live request against a running server actually
  gets rejected. Gap-backlog Patch 28 (Security Audit) is where that
  gets exercised against a real running instance (blocked in this
  sandbox: no live database/server to send real HTTP requests against -
  see that patch's own status).
- IDOR-style checks (e.g. citizen A can never reach citizen B's
  complaint by guessing an ID) are enforced by `requireCanView` /
  `ComplaintRatingService`/`ComplaintAppealService`'s own ownership
  checks, confirmed present by reading the service code (see this
  project's own `decisions-and-principles.md` and this audit's earlier
  findings), but not exercised end-to-end against a live server in this
  session either.

## Endpoints added in the Sep 2026 strict recheck

| Endpoint | Roles | Scope enforcement |
|---|---|---|
| `GET /api/v1/citizen/dashboard` (Patch 08) | CITIZEN | No id parameter - always the caller's own complaints |
| `GET /api/v1/officer/dashboard` (Patch 09) | GOVERNMENT_OFFICER, DEPARTMENT_HEAD, MAINTENANCE_TEAM | No id parameter - always the caller's own assigned work / own status-history actions |
| `POST /api/v1/complaints/{id}/confirm-resolution` (Patch 41) | CITIZEN | Own complaint only; RESOLVED -> CLOSED via ComplaintStateMachine |
| `POST /api/v1/complaints/{id}/reopen` body `{reason?}` (Patch 41) | CITIZEN | Unchanged rules; optional reason now recorded in status_history |
| `PATCH /api/v1/complaints/{id}/reject-budget` (Patch 15) | DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN | requireCanView (own department for heads) - same as approve-budget |
| `GET /api/v1/admin/ai-feedback/summary` (Patch 52) | ADMIN, SUPER_ADMIN | Aggregate only, no personal data |
| `GET /api/v1/reports/overview.pdf` (Patch 18) | DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN | Delegates to GovernmentDashboardService.getOverview - same department scoping as the dashboard/CSV |
| `/api/v1/auth/**` (Patch 50) | public | Now rate-limited per client IP + path (AuthEndpointRateLimitFilter), in addition to the per-user limiter for authenticated calls |

Flutter routing corrections in the same pass: VERIFICATION_TEAM now opens VerificationHomeScreen (verification queue + appeals) and MAINTENANCE_TEAM opens OfficerHomeScreen - both previously fell through to the CITIZEN shell. As before, client routing is convenience only; @PreAuthorize and service-layer scope checks are the enforcement.
