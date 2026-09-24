"""
Request/response schemas for POST /api/v1/ai/budget-predict
(SRS 20.3 Table 24, 15.9 Budget Prediction Module, 21.8 Budget Prediction).

COLD-START BY DEFINITION THIS PHASE: SRS 21.8 Input lists "historical
department cost/duration data" and Confidence Score is "derived from the
volume and recency of historical data available for the specific
category/severity/ward combination." This project has no historical
repair-cost/duration dataset anywhere (the Reports module - the documented
source, SRS 15.9 Dependencies - is Phase 16 and not built; no seed data
was ever loaded per PROJECT_PROGRESS.md's own outstanding-items list).
Every request this phase therefore hits SRS 21.8's own documented
Fallback Logic: "cold-start categories return a 'preliminary estimate -
low confidence' flag using citywide averages rather than ward-specific
data" - confidence_indicator is always "PRELIMINARY" this phase, honestly,
not a placeholder pretending otherwise. See app/services/budget_service.py
for the static citywide-average cost/duration table this produces
estimates from.

GUARDRAILS: SRS 15.9 Validation Rules requires "predicted values ... bounded
by configurable minimum/maximum guardrails per category" - enforced in
budget_service.py against app/config.py's budget_guardrail_min_inr/
budget_guardrail_max_inr.
"""
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.schemas.classify import IssueCategory


class BudgetPredictRequest(BaseModel):
    # --- SRS 20.3 Table 24 literal contract fields ---
    category: IssueCategory
    severity: str = Field(..., description="LOW | MEDIUM | HIGH | CRITICAL - the complaint's assigned severity.")
    ward_id: int | None = Field(
        default=None,
        description=(
            "SRS 21.8 Input: 'ward/zone'. No ward-level historical spend data "
            "exists yet (see module docstring) so this is accepted for audit/"
            "future-use only and does not change the estimate this phase - "
            "every estimate is a citywide average, not ward-specific."
        ),
    )

    # --- ADDITION beyond the literal Table 24 contract, for audit logging
    # only (same pattern as ClassifyRequest.complaint_id) ---
    complaint_id: int | None = None


class BudgetPredictResponse(BaseModel):
    # --- SRS 20.3 Table 24 / 21.8 literal output fields ---
    estimated_cost_min: float = Field(..., ge=0, description="Lower bound of the cost estimate (INR).")
    estimated_cost_max: float = Field(..., ge=0, description="Upper bound of the cost estimate (INR).")
    estimated_resolution_days: int = Field(..., ge=0)
    confidence: str = Field(
        ..., description="PRELIMINARY | STANDARD | HIGH (SRS 21.8 Confidence Score; always PRELIMINARY this phase - see module docstring)."
    )

    # --- Additions beyond the literal output list ---
    model_version: str = Field(..., description="SRS 19.6-style version string for this module.")
    guardrail_applied: bool = Field(
        ..., description="True if the raw category/severity-table estimate was clamped by the configured min/max guardrail."
    )
    raw_output: dict[str, Any] = Field(
        ..., description="Full estimation detail for audit/retraining (SRS 19.7 budget table's own audit purpose)."
    )
