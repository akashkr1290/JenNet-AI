"""
Priority Prediction Module (SRS 15.8, 21.6): assigns a severity level and
numeric priority score to a complaint that has proceeded past duplicate
checking (SRS 14.1 Workflow Narrative, item 5).

MODEL CHOICE - static rules-based scoring only, no trained ML component:
see app/schemas/priority_predict.py's module docstring for the full
reasoning (no historical resolution dataset exists anywhere in this
project - Reports module, the documented source, is Phase 16 and not
built). SRS 21.6 Fallback Logic's "conservative static severity table"
IS this module's real, complete implementation this phase, not a
placeholder standing in for something better - exactly like Phase 9's
dHash algorithm for Duplicate Detection.

ALGORITHM (SRS 15.8/21.6 Business Rules, applied in this exact order):
  1. Base severity - a static per-category table (BASE_SEVERITY_TABLE
     below). Every category in the locked IssueCategory set has an entry;
     GENERAL (unclassified/unmapped) defaults to the lowest tier.
  2. Safety-hazard override - SRS 15.8: "Critical is auto-assigned to any
     complaint involving a safety hazard category (e.g., open manhole,
     exposed live wire) regardless of other scoring inputs." Only
     OPEN_MANHOLE exists as a real category in this project's locked
     seven-value IssueCategory enum (POTHOLE, GARBAGE_OVERFLOW,
     WATER_LEAKAGE, BROKEN_STREET_LIGHT, OPEN_MANHOLE,
     ILLEGAL_CONSTRUCTION, GENERAL - see classify.py) - "exposed live
     wire" was never modelled as a distinct category anywhere in this
     locked stack (not in the SRS's own IssueCategory list either; it
     only appears as a parenthetical example). The configured
     safety-hazard category set (app/config.py) is therefore
     {OPEN_MANHOLE} today; documented here rather than silently
     narrowed, and Admin-configurable (env var) if a future phase adds
     more hazard categories. This override takes precedence over
     everything below it - applied unconditionally when the category
     matches, before location or corroboration weighting.
  3. Location-sensitivity weighting - SRS 15.8 Features: "location-
     sensitivity weighting (proximity to schools, hospitals, high-traffic
     roads)." No POI/geometry data source exists in this project (see
     schema module docstring), so every flag is honestly False from every
     caller today; this function still implements the real weighting
     logic (any true flag raises severity by one level, capped at
     CRITICAL) so it activates for real the moment a future phase wires a
     real data source in - same "build the real logic, mark the input
     unavailable" pattern as yolo_service.py.
  4. Corroboration-count bump - SRS 15.8: "corroboration count above a
     configured threshold raises severity by one level." Applied after
     location weighting, capped at CRITICAL, and skipped entirely if the
     safety-hazard override already forced CRITICAL (nothing to bump).
  5. Numeric priority_score - deterministic severity-to-score base
     (LOW=25, MEDIUM=50, HIGH=75, CRITICAL=100 - evenly spaced, not an
     SRS-literal formula since none is specified) plus a small
     corroboration bonus (+2 points per corroborating report beyond the
     first, capped at +10) so two complaints of the same severity can
     still be ordered by how many citizens reported the same issue -
     SRS 15.8 Outputs asks for both a severity level AND a distinct
     numeric priority score, so the score must carry more information
     than the four-value severity alone or it would be redundant.
"""
from __future__ import annotations

_SEVERITY_ORDER = ["LOW", "MEDIUM", "HIGH", "CRITICAL"]

# Step 1 - static per-category base severity table (SRS 21.6 Fallback Logic).
BASE_SEVERITY_TABLE: dict[str, str] = {
    "POTHOLE": "MEDIUM",
    "GARBAGE_OVERFLOW": "LOW",
    "WATER_LEAKAGE": "MEDIUM",
    "BROKEN_STREET_LIGHT": "LOW",
    "OPEN_MANHOLE": "CRITICAL",  # also always hit by the safety-hazard override below
    "ILLEGAL_CONSTRUCTION": "MEDIUM",
    "GENERAL": "LOW",
}

_SCORE_BY_SEVERITY = {"LOW": 25.0, "MEDIUM": 50.0, "HIGH": 75.0, "CRITICAL": 100.0}
_CORROBORATION_BONUS_PER_REPORT = 2.0
_CORROBORATION_BONUS_CAP = 10.0


def bump_severity(severity: str) -> str:
    """One level up the fixed LOW->MEDIUM->HIGH->CRITICAL scale, capped at CRITICAL."""
    idx = _SEVERITY_ORDER.index(severity)
    return _SEVERITY_ORDER[min(idx + 1, len(_SEVERITY_ORDER) - 1)]


def compute_priority_score(severity: str, corroboration_count: int) -> float:
    """Step 5 above. corroboration_count is expected >=1 (the complaint itself counts as one report)."""
    base = _SCORE_BY_SEVERITY[severity]
    extra_reports = max(0, corroboration_count - 1)
    bonus = min(extra_reports * _CORROBORATION_BONUS_PER_REPORT, _CORROBORATION_BONUS_CAP)
    return round(min(base + bonus, 100.0), 2)


def score_complaint(
    *,
    category: str,
    corroboration_count: int,
    location_flags: dict[str, bool],
    safety_hazard_categories: set[str],
    corroboration_severity_threshold: int,
) -> dict:
    """
    Pure-function core of the module (no pydantic dependency, so it can be
    unit-tested with real execution in this sandbox even though
    pydantic/fastapi themselves remain uninstallable here - see
    PROJECT_PROGRESS.md TESTS). Returns a plain dict; the route layer
    (routes/priority_predict.py) wraps this into PriorityPredictResponse.

    :param category: IssueCategory value string.
    :param corroboration_count: SRS 15.6-fed input; >=1.
    :param location_flags: e.g. {"near_school": bool, "near_hospital": bool, "high_traffic_road": bool}.
    :param safety_hazard_categories: category strings that force CRITICAL (Step 2).
    :param corroboration_severity_threshold: Step 4's configured threshold.
    """
    base_severity = BASE_SEVERITY_TABLE.get(category, "LOW")
    severity = base_severity

    safety_hazard_override_applied = category in safety_hazard_categories
    if safety_hazard_override_applied:
        severity = "CRITICAL"

    if not safety_hazard_override_applied and any(location_flags.values()):
        severity = bump_severity(severity)

    corroboration_bump_applied = False
    if not safety_hazard_override_applied and corroboration_count > corroboration_severity_threshold:
        if severity != "CRITICAL":
            severity = bump_severity(severity)
        corroboration_bump_applied = True

    priority_score = compute_priority_score(severity, corroboration_count)

    return {
        "severity": severity,
        "priority_score": priority_score,
        "safety_hazard_override_applied": safety_hazard_override_applied,
        "corroboration_bump_applied": corroboration_bump_applied,
        "base_severity": base_severity,
        "location_flags_applied": {k: v for k, v in location_flags.items() if v},
    }
