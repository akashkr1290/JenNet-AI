"""
Request/response schemas for POST /api/v1/ai/priority-predict
(SRS 20.3 Table 24, 15.8 Priority Prediction Module, 21.6 Severity Prediction).

MODEL CHOICE - static rules-based scoring, not a trained ML model: SRS
15.8 Features calls this a "rules-and-ML hybrid" module, and 21.6 Fallback
Logic says "insufficient historical data for a category defaults to a
conservative static severity table until sufficient training data
accumulates." This project has never seeded or trained on any historical
resolution dataset (see PROJECT_PROGRESS.md/ARCHITECTURE.md - Reports
module, the documented historical-data source, is Phase 16 and not built),
so every request this phase is a cold-start request by definition - the
static rules-based table IS the honest, currently-correct implementation,
the same "build the real always-available capability, don't fabricate the
ML half" choice Phase 9 made for duplicate detection (dHash instead of a
learned embedding). See app/services/priority_service.py's module
docstring for the full scoring algorithm.

LOCATION-SENSITIVITY - always reported unavailable this phase: SRS 15.8
Features lists "location-sensitivity weighting (proximity to schools,
hospitals, high-traffic roads)" as an input. No POI/geometry data source
exists anywhere in this project's schema (Ward has only a boundary_geojson
column, unused since V1 per that migration's own header comment - no
schools/hospitals/traffic layer was ever added). Rather than fabricate
sensitivity flags with no real data behind them, `location_flags` is
modelled as present-but-honestly-empty/false by the caller (mirrors
ClassifyResponse.preprocessed_image_reference always being null in Phase 7
for the same "the input structurally doesn't exist yet" reason). See
priority_service.py for how an all-false flag set degrades gracefully
(zero weighting contribution, not an error).
"""
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.schemas.classify import IssueCategory


class LocationSensitivityFlags(BaseModel):
    """
    SRS 15.8 Features: "proximity to schools, hospitals, high-traffic
    roads." No data source for these exists in this project yet (see
    module docstring) - every field defaults to False/unavailable, which
    is a legitimate, non-error input, not a missing-required-field error.
    """

    near_school: bool = False
    near_hospital: bool = False
    high_traffic_road: bool = False


class PriorityPredictRequest(BaseModel):
    # --- SRS 20.3 Table 24 literal contract fields ---
    category: IssueCategory
    corroboration_count: int = Field(
        default=1, ge=1, description="SRS 15.6: merged duplicates increase this, feeding Priority Prediction."
    )
    location_flags: LocationSensitivityFlags = Field(default_factory=LocationSensitivityFlags)

    # --- ADDITION beyond the literal Table 24 contract, for audit logging
    # only (same pattern as ClassifyRequest.complaint_id) ---
    complaint_id: int | None = None


class PriorityPredictResponse(BaseModel):
    # --- SRS 20.3 Table 24 / 21.6 literal output fields ---
    severity: str = Field(..., description="LOW | MEDIUM | HIGH | CRITICAL (SRS 15.8 Business Rules).")
    priority_score: float = Field(..., ge=0, le=100, description="Numeric priority score, 0-100 (SRS 15.8 Outputs).")

    # --- Additions beyond the literal output list, same "caller needs to
    # know *why*" pattern as ClassifyResponse.routing_reason ---
    safety_hazard_override_applied: bool = Field(
        ..., description="True when severity was forced to CRITICAL by the SRS 15.8 safety-hazard rule."
    )
    corroboration_bump_applied: bool = Field(
        ..., description="True when corroboration_count crossed the configured threshold and raised severity by one level."
    )
    base_severity: str = Field(..., description="The static-table severity before any override/bump was applied.")
    model_version: str = Field(..., description="SRS 19.6 predictions.model_version value for this module.")
    raw_output: dict[str, Any] = Field(
        ..., description="Full scoring detail for audit/retraining - maps onto predictions.raw_model_output (SRS 19.6)."
    )
