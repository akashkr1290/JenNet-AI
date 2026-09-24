# Remaining-Gaps Audit (15 items)

Audited against the current codebase and the SRS. **The SRS text itself is not in this
repository**; SRS requirements were taken from the passages quoted verbatim in the living
docs (`PROJECT_INTEGRATION.md` §6, `ARCHITECTURE.md`) and in code comments. Where the SRS
is silent in those sources, no new requirement was invented.

| # | Gap | Initial Status | Action Taken | Final Status |
|---|-----|----------------|--------------|--------------|
| 1 | AI model quality evaluation | PARTIAL | `scripts/evaluate_model.py` + procedure in `docs/AI_MODEL_EVALUATION.md` | IMPLEMENTED |
| 2 | Gemini production hardening | PARTIAL | Error/secret sanitisation, structured verdict parsing, bounded retry, HTTP timeout, tests | IMPLEMENTED |
| 3 | GPS / manual fallback | PARTIAL | Ward-only submission (`WARD_FALLBACK`), V23 migration, app flow, duplicate-check safety | IMPLEMENTED |
| 4 | Notification preference enforcement | PARTIAL | PUSH now dispatched and gated by `push_enabled`; tests | IMPLEMENTED |
| 5 | Redis shared analytics cache | NOT REQUIRED BY SRS | None (see below) | NOT REQUIRED BY SRS |
| 6 | Horizontal-scaling readiness | NOT REQUIRED BY SRS | Readiness checklist documented below; no code | NOT REQUIRED BY SRS |
| 7 | ML severity prediction | PARTIAL | None - SRS fallback already implemented; no data | BLOCKED |
| 8 | ML budget prediction | PARTIAL | None - SRS cold-start fallback already implemented; no data | BLOCKED |
| 9 | ML resolution-time prediction | PARTIAL | None - SRS fallback already implemented; no data | BLOCKED |
| 10 | Predictive trend forecasting | PARTIAL | `TrendForecaster` in the nightly analytics snapshot; API + dashboard | IMPLEMENTED |
| 11 | Continuous AI training pipeline | PARTIAL | Dataset prep -> train -> evaluate -> gate -> human approve -> promote scripts | IMPLEMENTED |
| 12 | Persistent AI model monitoring | PARTIAL | Timing persisted in `raw_model_output`; `GET /api/v1/admin/ai-feedback/monitoring` | IMPLEMENTED |
| 13 | Automated model rollback | PARTIAL | `scripts/model_registry.py` promote/rollback with sha256 checks + audit history; `USE_MODEL_REGISTRY` | IMPLEMENTED |
| 14 | Accessibility | PARTIAL | Live-region errors, labels, tooltips, semantics; static checker in CI (16 -> 0) | IMPLEMENTED |
| 15 | Privacy controls | PARTIAL | Contact details masked in logs/exception messages; Gemini errors no longer echoed | IMPLEMENTED |

## Findings and changes per item

**1. Evaluation.** Existed: `docs/AI_MODEL_EVALUATION.md` (checkpoint validation metrics only) and
`generate_model_manifest.py`. Missing: any reproducible test-set procedure. Added
`evaluate_model.py`: per-class precision/recall/F1/mAP50/mAP50-95 on a labelled YOLO test set,
difficult-condition subsets (`--condition name=yaml`), latency (mean/p50/p95), model sha256 and
dataset sha256 provenance, and checkpoint metrics copied only as a clearly labelled reference.
No metrics were produced or claimed: **no labelled test set exists in this project** (blocker).

**2. Gemini.** Found: the raw SDK exception text was returned in `fallback_reason` (persisted in
`predictions.raw_model_output`) and logged verbatim; agreement was a substring test ("not a
pothole" counted as agreeing with "pothole"); no retry; the timeout abandoned but did not bound
the HTTP call; no tests. Changed: only the exception type is returned, logs are key-redacted,
the prompt requests a JSON verdict and agreement is taken only from an explicit boolean
(otherwise unknown), one bounded retry for transient 429/503/deadline errors
(`GEMINI_MAX_RETRIES`), `request_options.timeout`. Same single integration; no new client.

**3. GPS fallback.** Found: manual mode required the citizen to type latitude/longitude. Wards
store a boundary polygon, and ai-service already implements SRS 15.6's "GPS unavailable" duplicate
rule. Changed: coordinates are optional when a ward is chosen; the server stores the ward-boundary
centroid (or the configured municipal-area centre when a ward has no boundary) with source
`WARD_FALLBACK` (new V23 migration widens the CHECK constraint); duplicate detection sends no
coordinates for approximate locations so the SRS no-GPS rule applies instead of false 50 m
matches. GPS and manually-entered coordinates behave exactly as before.
Note: seeded wards have no boundary and `app.geo.*` defaults to India's extent - operators should
load ward boundaries and set `GEO_MIN/MAX_LAT/LNG` to the municipality.

**4. Notification preferences.** SMS opt-out was enforced; email is intentionally mandatory for
status alerts (SRS 15.13 Exceptions, documented in `NotificationPreferenceKey`). PUSH was never
dispatched, so `push_enabled` had no effect. Now every trigger sends PUSH when push delivery is
configured and the user has not opted out. 6 tests added.

**5. Redis / 6. Horizontal scaling.** `ARCHITECTURE.md` §7 excludes Redis "unless a real,
demonstrated requirement ... is documented first"; no SRS passage requires multiple backend
instances, and the deployment is a single EC2 host. No code was added. Readiness checklist for a
future multi-instance deployment (all per-instance today):
- `AnalyticsCacheService` (in-process cache) - needs a shared cache;
- `RateLimitingFilter` / `AuthEndpointRateLimitFilter` - limits multiply by instance count;
- `@Scheduled` jobs (escalation sweep, analytics refresh, weekly report) would run on every
  instance - need a scheduler lock (e.g. ShedLock) or a single worker;
- `notificationExecutor` queue is in-memory - needs a durable queue;
- `STORAGE_PROVIDER=local` stores photos on the host - must be `s3`.
JWT authentication is stateless and needs no change.

**7-9. ML severity / budget / resolution time.** The SRS (21.6, 21.8, 15.8, 15.9 as quoted in
`PROJECT_INTEGRATION.md`) describes data-driven estimates whose confidence depends on historical
data, and explicitly defines the rule-based fallback for insufficient history. That fallback is
implemented (`priority_service.py`, `budget_service.py`, estimates flagged PRELIMINARY). No
historical resolution/cost dataset exists, so a trained model cannot be built honestly. Existing
behaviour preserved; no fake model added. **Blocker: real historical data.**

**10. Forecasting.** SRS 15.14: "trend predictions are refreshed on a configurable schedule". Only
historical aggregation existed. Added `TrendForecaster`: weekly totals from the cached 90-day
category trend, ordinary-least-squares linear trend, next-7-days projection with an approximate 80%
interval; refuses (`INSUFFICIENT_HISTORY`) below 6 weeks / 8 complaints. Computed inside the
scheduled snapshot, exposed as `categoryForecast` on the dashboard overview (appended field) and
shown in the app. No accuracy is claimed.

**11. Training pipeline.** Existed: feedback export (image-level verdicts, no boxes). Added
`prepare_training_dataset.py` (only human-confirmed rows; requires human box annotations, lists
the rest in `needs_annotation.csv`; deterministic splits; refuses tiny datasets),
`train_candidate.py` (registers a *candidate*, never activates it), `evaluation_gate.py` (same
test set, no mAP drop, bounded per-class recall drop), and human `approve` / `promote` in
`model_registry.py`. **Blockers: annotated dataset and GPU; training was not executed.**

**12. Monitoring.** ai-service's monitor is in-memory. Predictions are already persisted, so
per-stage timing was added to `raw_model_output`, and a new admin endpoint summarises per model
version: predictions, mean confidence, human-review rate, model-unavailable count, latency
p50/p95. No personal data is read.

**13. Rollback.** Previously: hand-edit env vars. Now `model_registry.py` provides
register/approve/promote/rollback with sha256 verification (a tampered artifact is refused),
immutable versions, no deletion, and an audit `history`. `USE_MODEL_REGISTRY=true` makes
ai-service load the registry's active version (default off = unchanged behaviour). Rollback is a
human action by design - no threshold-triggered auto-rollback without a reliable signal.

**14. Accessibility.** Found 16 issues across 10 files (static check): errors never announced
(10 occurrences, hard-coded red), unlabelled images, an unnamed icon button and tap target. Added
`ErrorText` (live region, theme error colour), semantic labels, tooltip, `Semantics` wrapper, and
`flutter/tool/check_accessibility.py` wired into `flutter-ci.yml`. OS text scaling was already
unclamped. Not verified with a real screen reader (needs a device).

**15. Privacy.** Already present: privacy/terms screens, signed media URLs, device-token
deletion endpoint, no OTP/password logging. Found: email addresses, phone numbers and message
bodies in stub-gateway logs, contact details inside delivery exception messages (logged on every
retry), and the super-admin mobile number in the bootstrap log. All masked via `PiiMask`. No SRS
passage requiring account deletion, retention periods or consent records was found; none added.

## Verification (actually executed)
- ai-service pytest: 107 passed (82 existing + 25 new, incl. real YOLO latency and evaluation-path runs).
- Java: new pure classes and 3 test classes compiled with javac 21 (`-Xlint:all -Werror`) and run: 14/14 passed
  (JUnit API supplied by a minimal local stub; the JUnit engine itself was not available).
- Flyway: 23/23 migrations on MySQL 8.0.46 and H2 2.2.224 (MySQL mode); V23 accepts WARD_FALLBACK and still rejects unknown sources.
- Syntax: 242 Java and 70 Dart files parsed with 0 errors (tree-sitter). Accessibility checker: 0 violations.
- NOT EXECUTED - ENVIRONMENT LIMITATION: `mvn verify` (Maven Central unreachable; the new
  NotificationServiceTest cases were not run), `flutter analyze`/`flutter test` (no Flutter SDK),
  live Gemini calls (no API key), model training (no dataset/GPU).
