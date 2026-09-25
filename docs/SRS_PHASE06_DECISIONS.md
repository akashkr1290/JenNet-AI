# Audit fix Phase 06 — SRS compliance: decisions, limits and open items

Gaps covered: GAP-020, GAP-031, GAP-033, GAP-037, GAP-038, GAP-039, GAP-040,
GAP-043, GAP-044, GAP-051, GAP-054. New migrations: `V28__create_report_snapshots.sql`
and `V29__create_sensitive_zones.sql`.

This file records every point where the SRS left a choice open, where an
external data source or provider is needed, and what was deliberately not
built. Code comments refer to it.

## GAP-020 — Department and ward/zone configuration (SRS 15.11, 13.4, 16.3)

Built:
- `GET/POST /api/v1/admin/departments`, `PUT /{id}`, `PATCH /{id}/status`.
- `GET/POST /api/v1/admin/wards`, `PUT /{id}`, `PATCH /{id}/status`.
- Flutter: Admin → **Setup** → Departments / Wards.

Rules:
- Ward boundaries are validated before saving (`WardBoundaryValidator`). A
  boundary must be a GeoJSON Polygon or MultiPolygon, or a Feature holding one.
  Rings must be closed, have at least 3 distinct corners, stay in range, have
  non-zero area and must not cross themselves. Holes must lie inside their
  polygon, and MultiPolygon parts must not overlap. The limit is 10,000
  positions.
- A boundary that overlaps the area of another active ward is rejected with 409.
  A shared border is allowed.
- The saved boundary feeds the Phase 04 `WardLocator` directly. There is no
  cache, so the change applies to the next complaint.
- Rows are never deleted, only deactivated. Every change is audit-logged with
  the previous values, including the full previous boundary. This covers
  "configuration changes versioned and logged".
- Departments:
  - The configured fallback department cannot be renamed or deactivated.
  - A department still targeted by an active routing rule cannot be
    deactivated.
  - The head must be an ACTIVE `DEPARTMENT_HEAD` account that already belongs
    to the department.

Limits and open items:
- **No map editor.** `pubspec.yaml` has no map package, and `pubspec.lock`
  could not be regenerated here. Boundaries are pasted as GeoJSON, for example
  exported from QGIS or geojson.io.
  - To add a drawn editor: `flutter pub add flutter_map latlong2`, regenerate
    `pubspec.lock`, and choose a tile provider. The tile provider is
    **external configuration**, and OSM's usage policy applies.
- **"Undefined zones".** This is read as "a boundary must be a well-defined
  polygon". Coverage gaps between wards cannot be detected because no
  municipal outline polygon is configured, only the `GEO_*` bounding box.
  - **Product-owner decision:** provide the official municipal outline if gap
    detection is wanted.
- **Seeded wards.** "Ward 1..3" have no boundary. The real ward boundaries
  must be loaded by the operator. This is **external data**.

## GAP-031 — GPS module (SRS 15.5, 16.1, 17.2)

Built:
- **EXIF GPS fallback.**
  - `ExifGps` reads GPSLatitude/Longitude and their refs from the original
    upload. It reuses the Phase 04 APP1/TIFF walker in `ExifOrientation`.
  - `ComplaintService.create` reads it **before** `validateAndSanitize`
    re-encodes the image, because the re-encode strips all metadata.
  - Precedence: device/manual coordinates first, then EXIF GPS (source
    `EXIF`), then the ward centroid (`WARD_FALLBACK`).
  - EXIF coordinates get the same jurisdiction check as device GPS.
  - `0,0`, malformed and out-of-range values are ignored. Only JPEG is read.
- **Out-of-jurisdiction review queue.**
  - `GET /api/v1/admin/out-of-jurisdiction` lists open complaints with
    `out_of_jurisdiction = true`.
  - `POST /{complaintId}/accept {wardId}` assigns the ward and clears the
    flag, and is audited.
  - A complaint that really belongs elsewhere is rejected through the normal
    verification flow, with a reason, so the citizen is notified.
  - Flutter: Admin → Setup → Jurisdiction review.
- Nearest-ward fallback already existed (Phase 04, `WardLocator`).

Not built, or external:
- **Jurisdiction boundary.** The `GEO_MIN/MAX_LAT/LNG` default is still the
  all-India placeholder. The real municipal boundary is
  **external configuration**; it was not invented.
- **Map pin-drop.** This needs a map package; see GAP-020. Manual coordinates
  plus ward selection remain the fallback.
- **Street-address reverse geocoding.** No geocoding provider is integrated.
  This is external.
- **On-device EXIF availability is NOT VERIFIED.**
  - Camera photos carry GPS only when the camera app's location tagging is on.
  - On Android 10+ the OS removes location from gallery images unless the app
    holds `ACCESS_MEDIA_LOCATION`, which this app does not request.
  - Whether to request that permission is a **product-owner decision**
    (privacy notice).

## GAP-033 — Location sensitivity and recalibration (SRS 15.8, 15.9)

Built:
- `sensitive_zones` (V29) and `GET/POST /api/v1/admin/sensitive-zones`,
  `PATCH /{id}/status`.
  - An Admin records schools, hospitals and high-traffic roads as circles
    (centre plus radius, 10–5000 m).
  - Flutter: Admin → Setup → Sensitive zones.
- `PriorityBudgetPredictionService` now sends real flags
  (`SensitiveZoneService#flagsFor`) instead of the constant `NONE_AVAILABLE`.
  - ai-service already raises severity one level when any flag is true.
  - Approximate `WARD_FALLBACK` locations never produce flags.

External or not built:
- **POI data source.** No OpenStreetMap or municipal school/hospital/road layer
  is integrated. Zones are entered by an Admin.
  - Importing a POI layer is an **external data requirement**, including the
    licence (OSM ODbL attribution) and an import job.
- **Historical recalibration.** This is NOT implemented, because the data it
  needs does not exist.
  - The system stores only cost **estimates** and approvals. No actual spend
    or actual resolution cost is recorded anywhere.
  - Recalibrating from estimates would be circular.
  - Prerequisites are a **product-owner decision** plus a new field for actual
    cost at resolution. After that, a periodic job can recalibrate
    `budget_service.py` tables by category and severity, keeping the
    cold-start table.
  - Severity ML training needs labelled outcomes; see the Phase 04 AI docs.

## GAP-037 — Maintenance mode and announcements (SRS 15.11, 15.1 Exceptions)

Built:
- PLATFORM settings rows `maintenance_mode`, `maintenance_message`,
  `maintenance_retry_after_minutes` and `announcement_text`.
  - `GET /api/v1/public/platform-status` needs no token.
  - `GET/PUT /api/v1/admin/platform-status` is Admin-only and audited.
- `MaintenanceModeFilter` answers non-admin writes (POST, PUT, PATCH, DELETE)
  with `503 {error:"MAINTENANCE"}` and a `Retry-After` header.
  - Reads are not blocked. Neither is `/api/v1/auth/**`, so sessions still
    work, nor actuator, nor Admin/Super Admin requests.
  - The state is cached for 5 s per instance.
- Flutter:
  - A banner (maintenance plus announcement) appears in every signed-in shell
    and on the auth screens.
  - A complaint refused with 503 MAINTENANCE goes into the existing offline
    queue and is retried every 60 s. The queue no longer drops an item that
    gets a maintenance refusal.
  - Admin → Setup → Maintenance edits all of this.

Limits:
- Texts are limited to 200 characters (the `settings.value` column).
- Queued retry works only while the app is running, and only on Android.
  - Web has no durable file path; this is the same limit as the offline queue.
- There is no scheduled start or end time for maintenance windows. The SRS does
  not require one. An Admin switches it on and off.

## GAP-038 — Officer availability (SRS 15.7 inputs, 15.15)

- Automatic assignment skips officers whose `officer_availability_status` is
  `BUSY` or `ON_LEAVE`. There is one query for all candidates.
- Officers who never set the status count as AVAILABLE, the existing default.
- If every officer is unavailable, the complaint stays at department level and
  the Department Head is notified. This is the existing SRS 15.7 exception
  path.
- Manual assignment by a Department Head is unaffected.
- Excluding `BUSY` as well as `ON_LEAVE` follows the audit's expectation
  ("only AVAILABLE officers").
  - **Product-owner decision** if BUSY officers should still receive work.

## GAP-039 — Reports (SRS 15.12, 21, US-10)

Built:
- `GET /api/v1/reports/period` returns JSON; `/period.pdf` and `/period.csv`
  are also available. Parameters:
  - `type=DAILY|WEEKLY|CUSTOM`, with `from`/`to` in ISO dates in
    `app.reports.zone` (Asia/Kolkata).
  - DAILY defaults to yesterday.
  - WEEKLY is the 7 days ending yesterday (US-10 "prior 7 days").
  - CUSTOM needs both dates.
- Range validation: the maximum is `REPORTS_MAX_RANGE_DAYS`, default 366
  ("1 year"). A period cannot end in the future.
- Contents:
  - Received, verified, assigned, resolved, closed, rejected and escalated in
    the period.
  - SLA compliance of resolutions in the period against the persisted
    `sla_due_at`, and average resolution hours.
  - By category, and by ward ("top recurring locations").
  - Budget estimates vs approved ranges by category.
  - Citizen engagement: distinct submitters, rating count and average.
- **Immutable snapshots** (V28):
  - Every generated report is stored.
  - A request for a period that has ended is answered from the first stored
    snapshot, so the same figures come back every time.
  - The entity is `@Immutable` and the repository has no update or delete
    methods.
  - `GET /api/v1/reports/snapshots`, `/{id}`, `/{id}/pdf`, `/{id}/csv`.
- **Insufficient data**: fewer received complaints than
  `REPORTS_MIN_COMPLAINTS` gives a report labelled INSUFFICIENT DATA in JSON,
  PDF and CSV.
- The weekly Department Head e-mail (Monday 08:00 IST) now sends the WEEKLY
  period report instead of the 90-day overview.
- Scope is the same as the dashboard: a Department Head sees only their own
  department.
- Flutter: a Reports action in the Department Head and Admin shells. The CSV is
  copied to the clipboard; the app has no file-save plugin.

Decisions and limits:
- **`REPORTS_MIN_COMPLAINTS` default is 1.** The SRS gives no number, so only an
  empty period is labelled by default.
  - **Product-owner decision** for a higher threshold.
- **Point-in-time figures** use creation and transition timestamps.
  - Two values are "as at generation": a complaint's department (if it was
    reassigned later) and budget approval status.
  - The stored snapshot, not recomputation, is authoritative.
- **Not built:**
  - Per-role configurable schedules (daily/weekly/monthly per recipient
    role). Only the existing weekly Department Head e-mail runs.
  - Government-officer daily reports.
  - Officer scorecards. The Department Performance view and CSV already cover
    officer workload.
  - Reputation distribution.
  - Analytics reports (forecasts, before/after, duplicate-merge statistics).
  - Ward filter.

  Status: **PARTIALLY_FIXED**.

## GAP-040 — Queue ordering (SRS 16.2, 16.3, 24.4)

Built:
- `GET /api/v1/complaints?sort=NEWEST|SEVERITY|SLA_DUE`.
  - SEVERITY orders Critical → Low, then soonest SLA.
  - SLA_DUE orders by the persisted `sla_due_at` (Phase 05), overdue first
    and no-clock last.
  - The default stays NEWEST, for backward compatibility.
  - Citizens always get NEWEST.
- `slaDueAt` was added to the list items.
- Flutter officer queue:
  - A "Sort by" control, defaulting to SLA due.
  - SLA countdown chips: "Due in 5h 30m" or "Overdue by 45m", with icon and
    text, not colour alone.
  - Critical and High are labelled.

Not built: KPI drill-down navigation, bulk-action checkboxes, and the extra
24.4 KPIs (budget utilisation, citizen satisfaction, escalation frequency on
the dashboard). The period report now contains budget, satisfaction and
escalation figures. Status: **PARTIALLY_FIXED**.

## GAP-051 — Stale status-history text

New complaints get "Queued for AI processing".

`status_history` is append-only, so existing rows keep their stored text.
`StatusHistoryResponse` shows the old developer note as the new text. No
migration rewrites history.

## GAP-054 — Name validation and profanity filter (SRS 17.1, 17.2)

- **Full name** (register, admin create user, profile update):
  - 2–100 characters: Unicode letters, including combining marks for
    Devanagari and other Indian scripts, with single spaces between words.
  - Backend `@PersonName`; Flutter `validatePersonName`.
  - **Product-owner decision:** the SRS says "alpha + spaces", so ".", "-" and
    "'" are rejected (for example "A. Kumar", "Mary-Jane", "D'Souza"). Relax
    `PersonNames` if those should be allowed.
  - Existing accounts are unaffected until their name is edited. A profile
    update then needs a conforming name.
- **Description profanity filter** (`DescriptionProfanityFilter`):
  - Matching is whole-word, case-insensitive and NFKC-normalised, and works
    for any script. Multi-word phrases are supported.
  - The action is `COMPLAINT_PROFANITY_ACTION=REJECT` (400, the default) or
    `MASK` (stored as `****`).
  - **No word list ships.** Choosing it is a **product-owner decision**: which
    languages, and transliterated Hindi or not. Provide it with
    `COMPLAINT_PROFANITY_WORDS` (comma-separated) or
    `COMPLAINT_PROFANITY_WORDLIST_PATH` (a file with one entry per line).
  - With no list, the filter is off and says so in a startup WARN. It never
    pretends to filter.

## GAP-043 / GAP-044 — Tests and Postman

- Tests are listed in `/home/claude/fixes/verification/LEDGER.md` (Phase 06).
- Postman: see `postman/README.md` "2026 audit update". It has 106 requests;
  every backend mapping is present. ai-service `/api/v1/ai/quality` is
  documented as internal.
