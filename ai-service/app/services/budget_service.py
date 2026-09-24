"""
Budget Prediction Module (SRS 15.9, 21.8): estimates a repair cost range
and expected resolution time for a complaint, once its category and
severity are known (SRS 15.9 Inputs; Dependencies: Priority Prediction
Module - this module is always called after priority-predict, never
before, both in this service's own call order via routes/budget_predict.py
callers and in the backend's orchestration - see
service/complaint/PriorityBudgetPredictionService.java).

COLD-START BY DEFINITION THIS PHASE - see app/schemas/budget_predict.py's
module docstring for the full reasoning (no historical department
cost/duration dataset exists anywhere in this project). Every estimate
produced here is honestly a citywide-average PRELIMINARY figure (SRS 21.8
Fallback Logic's own literal words), not a fabricated STANDARD/HIGH
figure. `confidence` is hardcoded to "PRELIMINARY" for exactly that
reason - there is currently no code path in this module that could ever
legitimately return anything else, and it would be dishonest to leave a
STANDARD/HIGH branch in place with no real data source feeding it.

ALGORITHM:
  1. BASE_COST_TABLE - a static per-category (min, max) INR range,
     representing a MEDIUM-severity citywide average. Values are
     illustrative planning estimates (SRS Out of Scope: "not a financial
     commitment"), not sourced from any real municipal dataset - no such
     dataset was supplied to this project (see SRS Section on historical
     data seeding, "will be seeded from publicly available municipal
     records or reasonable synthetic values" - that seeding work itself is
     out of scope for this phase and not yet done by any prior phase).
  2. SEVERITY_COST_MULTIPLIER - scales the MEDIUM baseline up/down for the
     complaint's actual severity (a CRITICAL safety issue costs more to
     fix urgently than a LOW one of the same category).
  3. Guardrail clamp - SRS 15.9 Validation Rules: "predicted values are
     bounded by configurable minimum/maximum guardrails ... to prevent
     outlier predictions." IMPLEMENTED AS A GLOBAL FLOOR/CEILING
     (app/config.py budget_guardrail_min_inr/max_inr), not a genuinely
     per-category configurable table - the SRS's literal wording asks for
     per-category guardrails, but no Admin-configuration mechanism exists
     yet for that granularity (the Admin Module, SRS 15.11, is Phase 14
     and not built). A single global sanity clamp is applied uniformly
     instead; documented here as a real, acknowledged gap versus the
     SRS's literal per-category wording, not hidden.
  4. RESOLUTION_DAYS_BY_SEVERITY - a static per-severity resolution-time
     estimate. Loosely follows the same urgency ordering as SRS 14.3's
     escalation SLA thresholds (Critical fastest, Low slowest) for
     internal consistency, but is a distinct, independently-configured
     figure - an escalation SLA and an expected-resolution-time estimate
     are two different things and are not meant to be numerically
     identical.
"""
from __future__ import annotations

# Step 1 - static per-category (min, max) INR range at MEDIUM severity.
BASE_COST_TABLE: dict[str, tuple[float, float]] = {
    "POTHOLE": (2000.0, 15000.0),
    "GARBAGE_OVERFLOW": (1000.0, 8000.0),
    "WATER_LEAKAGE": (5000.0, 40000.0),
    "BROKEN_STREET_LIGHT": (1500.0, 10000.0),
    "OPEN_MANHOLE": (8000.0, 60000.0),
    "ILLEGAL_CONSTRUCTION": (10000.0, 100000.0),
    "GENERAL": (1000.0, 20000.0),
}

# Step 2 - severity multiplier applied to the MEDIUM baseline above.
SEVERITY_COST_MULTIPLIER: dict[str, float] = {
    "LOW": 0.6,
    "MEDIUM": 1.0,
    "HIGH": 1.5,
    "CRITICAL": 2.2,
}

# Step 4 - static per-severity expected resolution time.
RESOLUTION_DAYS_BY_SEVERITY: dict[str, int] = {
    "CRITICAL": 2,
    "HIGH": 4,
    "MEDIUM": 8,
    "LOW": 15,
}


def estimate_budget(
    *,
    category: str,
    severity: str,
    guardrail_min: float,
    guardrail_max: float,
) -> dict:
    """
    Pure-function core of the module (no pydantic dependency - see
    priority_service.py's matching docstring note on why this split
    exists and how it's tested). Returns a plain dict; the route layer
    (routes/budget_predict.py) wraps this into BudgetPredictResponse.
    """
    base_min, base_max = BASE_COST_TABLE.get(category, BASE_COST_TABLE["GENERAL"])
    multiplier = SEVERITY_COST_MULTIPLIER.get(severity, 1.0)

    raw_min = base_min * multiplier
    raw_max = base_max * multiplier

    clamped_min = max(guardrail_min, min(raw_min, guardrail_max))
    clamped_max = max(guardrail_min, min(raw_max, guardrail_max))
    # Guardrail could theoretically invert min/max if guardrail_max < a
    # category's raw_min (misconfigured .env) - defensively re-sort so the
    # DB's own chk_budget_cost_range CHECK (max >= min) is never violated.
    if clamped_min > clamped_max:
        clamped_min, clamped_max = clamped_max, clamped_min

    guardrail_applied = (clamped_min != round(raw_min, 2)) or (clamped_max != round(raw_max, 2))

    resolution_days = RESOLUTION_DAYS_BY_SEVERITY.get(severity, RESOLUTION_DAYS_BY_SEVERITY["MEDIUM"])

    return {
        "estimated_cost_min": round(clamped_min, 2),
        "estimated_cost_max": round(clamped_max, 2),
        "estimated_resolution_days": resolution_days,
        # Step-3 docstring note: always PRELIMINARY this phase - see module docstring.
        "confidence": "PRELIMINARY",
        "guardrail_applied": guardrail_applied,
        "raw_cost_min_before_guardrail": round(raw_min, 2),
        "raw_cost_max_before_guardrail": round(raw_max, 2),
    }
